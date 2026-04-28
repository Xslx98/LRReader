package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.annotation.VisibleForTesting
import com.hippo.ehviewer.download.DownloadStateConverter
import com.lanraragi.reader.domain.Archive
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * Room database replacing GreenDAO's DaoMaster/DaoSession.
 *
 * ## Migration 指南
 *
 * 未来修改数据库结构时：
 *
 * 1. 修改 Entity（增删改字段/表）
 * 2. version 加 1
 * 3. 编写 Migration 对象，例如：
 *    ```
 *    private val MIGRATION_10_11 = object : Migration(10, 11) {
 *        override fun migrate(db: SupportSQLiteDatabase) {
 *            db.execSQL("ALTER TABLE ... ADD COLUMN ...")
 *        }
 *    }
 *    ```
 * 4. 在 databaseBuilder 中注册：`.addMigrations(MIGRATION_9_10, MIGRATION_10_11)`
 *
 * Room 会自动链式执行所需的 Migration。
 * **不要使用 fallbackToDestructiveMigration()，否则用户数据会丢失。**
 */
@Database(
    entities = [
        DownloadInfo::class,
        DownloadLabel::class,
        DownloadDirname::class,
        HistoryInfo::class,
        LocalFavoriteInfo::class,
        QuickSearch::class,
        ServerProfile::class,
        ArchiveLocalState::class
    ],
    version = 23,
    exportSchema = true
)
@TypeConverters(DateConverter::class, DownloadStateConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadRoomDao
    abstract fun browsingDao(): BrowsingRoomDao
    abstract fun miscDao(): MiscRoomDao
    abstract fun archiveLocalStateDao(): ArchiveLocalStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "eh.db"
                )
                    .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * v17 → v18: Drop the Gallery_Tags table.
         *
         * Gallery_Tags was an EhViewer-era per-gallery tag cache. Investigation
         * showed it was a dead cache: `insertGalleryTags` and `updateGalleryTags`
         * had ZERO callers anywhere in the codebase, so the table was never
         * populated. The only reader was `EhDB.queryGalleryTags` called once
         * from `DownloadListInfosExecutor.searchTagList` — which always got
         * null back and handled it as "no cache, no match". Meanwhile, real
         * tag data for LRR archives is populated directly into
         * `DownloadInfo.tgList` by `LRRArchive.toGalleryInfo()` from the LRR
         * API response, not via this cache table. The cache path was severed
         * during the LRR conversion and never reconnected.
         *
         * Deleting this table also lets us remove the last meaningful
         * `@JvmStatic blockingDb` bridge (`EhDB.queryGalleryTags`), bringing
         * the inventory down from 3 to 2 (only `getDownloadDirname` and
         * `putDownloadDirname` remain).
         */
        @VisibleForTesting
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS Gallery_Tags")
            }
        }

        /**
         * v18 → v19: Add indexes to LOCAL_FAVORITES, QUICK_SEARCH, and DOWNLOAD_LABELS.
         *
         * These tables are queried with ORDER BY TIME in several DAO methods.
         * Adding indexes on TIME improves sort performance as the dataset grows.
         */
        @VisibleForTesting
        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_LOCAL_FAVORITES_TIME` ON `LOCAL_FAVORITES` (`TIME`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_QUICK_SEARCH_TIME` ON `QUICK_SEARCH` (`TIME`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOAD_LABELS_TIME` ON `DOWNLOAD_LABELS` (`TIME`)")
            }
        }

        /**
         * v19 → v20: Migrate primary key from GID (Long) to ARCID (String).
         *
         * LANraragi's natural identifier is `arcid` (40-char hex SHA-1 string).
         * The old GID was a SHA-256 hash of arcid, stored as Long. The TOKEN
         * column already contains the arcid verbatim, so no data is lost.
         *
         * For each of the 4 affected tables:
         * 1. Create new table with ARCID TEXT as PK
         * 2. Copy data from old table (TOKEN → ARCID)
         * 3. Drop old table
         * 4. Rename new table
         * 5. Recreate indexes
         */
        /**
         * v20 → v21: Fix Entity PK annotations (GID → ARCID).
         *
         * MIGRATION_19_20 correctly created tables with PRIMARY KEY (ARCID),
         * but the Kotlin Entity annotations still declared primaryKeys = ["GID"].
         * Clean installs at v20 therefore got GID as PK instead of ARCID.
         * This migration detects and fixes that case by recreating the tables
         * with ARCID as PK. For users who upgraded through v19→v20 (already
         * have ARCID PK), this is a safe no-op — the recreated tables are
         * structurally identical.
         */
        /**
         * v21 → v22: Drop dead EH-era columns from DOWNLOADS / HISTORY /
         * LOCAL_FAVORITES.
         *
         * After W36 Phase 2 the three Entities are standalone Parcelables
         * and no longer inherit GalleryInfoEntity, but the Room schema
         * still carried five columns from the EhViewer ancestry that LRR
         * never populates: GID (Long, replaced by ARCID PK in v20),
         * TITLE_JPN, CATEGORY (always 0/-1), POSTED, UPLOADER (always
         * null). Drop them physically here.
         *
         * minSdk = 28 → no native ALTER TABLE DROP COLUMN (added in
         * SQLite 3.35 / Android 14). Use the recreate-table dance
         * established in MIGRATION_20_21:
         *   1. CREATE TABLE *_NEW with the slimmed column set
         *   2. INSERT SELECT the kept columns
         *   3. DROP the original table
         *   4. RENAME *_NEW → original
         *   5. CREATE INDEX entries
         *
         * **Irreversible**: once a user runs the new APK their database
         * file no longer carries those columns. A `git revert` of this
         * commit will work for the source tree but cannot recover the
         * dropped data on a user's device.
         */
        @VisibleForTesting
        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── DOWNLOADS ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS DOWNLOADS_NEW (
                        ARCID TEXT NOT NULL,
                        TITLE TEXT,
                        THUMB TEXT,
                        RATING REAL NOT NULL DEFAULT 0,
                        SIMPLE_LANGUAGE TEXT,
                        SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                        STATE INTEGER NOT NULL DEFAULT 0,
                        LEGACY INTEGER NOT NULL DEFAULT 0,
                        TIME INTEGER NOT NULL DEFAULT 0,
                        LABEL TEXT,
                        ARCHIVE_URI TEXT,
                        PRIMARY KEY (ARCID)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO DOWNLOADS_NEW
                    SELECT ARCID, TITLE, THUMB, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI
                    FROM DOWNLOADS
                """.trimIndent())
                db.execSQL("DROP TABLE DOWNLOADS")
                db.execSQL("ALTER TABLE DOWNLOADS_NEW RENAME TO DOWNLOADS")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_SERVER_PROFILE_ID` ON `DOWNLOADS` (`SERVER_PROFILE_ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_TIME` ON `DOWNLOADS` (`TIME`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_LABEL` ON `DOWNLOADS` (`LABEL`)")

                // ── HISTORY ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS HISTORY_NEW (
                        ARCID TEXT NOT NULL,
                        TITLE TEXT,
                        THUMB TEXT,
                        RATING REAL NOT NULL DEFAULT 0,
                        SIMPLE_LANGUAGE TEXT,
                        SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                        MODE INTEGER NOT NULL DEFAULT 0,
                        TIME INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (ARCID)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO HISTORY_NEW
                    SELECT ARCID, TITLE, THUMB, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, MODE, TIME
                    FROM HISTORY
                """.trimIndent())
                db.execSQL("DROP TABLE HISTORY")
                db.execSQL("ALTER TABLE HISTORY_NEW RENAME TO HISTORY")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_HISTORY_SERVER_PROFILE_ID` ON `HISTORY` (`SERVER_PROFILE_ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_HISTORY_TIME` ON `HISTORY` (`TIME`)")

                // ── LOCAL_FAVORITES ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS LOCAL_FAVORITES_NEW (
                        ARCID TEXT NOT NULL,
                        TITLE TEXT,
                        THUMB TEXT,
                        RATING REAL NOT NULL DEFAULT 0,
                        SIMPLE_LANGUAGE TEXT,
                        SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                        TIME INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (ARCID)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO LOCAL_FAVORITES_NEW
                    SELECT ARCID, TITLE, THUMB, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, TIME
                    FROM LOCAL_FAVORITES
                """.trimIndent())
                db.execSQL("DROP TABLE LOCAL_FAVORITES")
                db.execSQL("ALTER TABLE LOCAL_FAVORITES_NEW RENAME TO LOCAL_FAVORITES")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_LOCAL_FAVORITES_TIME` ON `LOCAL_FAVORITES` (`TIME`)")
            }
        }

        /**
         * v22 → v23: Introduce the `ARCHIVE_LOCAL_STATE` table and lift
         * existing rows from the three legacy tables (`DOWNLOADS`,
         * `HISTORY`, `LOCAL_FAVORITES`) into it. (L1)
         *
         * Pipeline:
         *   1. CREATE TABLE ARCHIVE_LOCAL_STATE + indexes
         *   2. Lift each `DOWNLOADS` row → INSERT (download fields set,
         *      history/favorite fields NULL). `archive_json` is built
         *      from the row's title/thumb/rating/server_profile_id; tags
         *      = empty (recovered on next detail load — see plan §5).
         *   3. Lift each `HISTORY` row → INSERT OR IGNORE then UPDATE
         *      history columns. The IGNORE step prevents clobbering an
         *      existing row already lifted from DOWNLOADS; the UPDATE
         *      then writes history columns whether the row was new or
         *      pre-existing. Same idempotent pattern is used for
         *      LOCAL_FAVORITES. Necessary because minSdk = 28 → SQLite
         *      3.22 → no `ON CONFLICT DO UPDATE` (which arrived in
         *      SQLite 3.24).
         *   4. (DROP of the three legacy tables is deferred to L1-4
         *      with a v23→v24 bump. Removing entities from
         *      `AppDatabase.entities` shifts Room's compiled identity
         *      hash; that change must coincide with a version bump so
         *      the per-DB stored identity hash is updated cleanly. The
         *      legacy tables therefore stay around — unused — between
         *      L1-2 and L1-4. Acceptable footprint: a few KB per device.)
         *
         * **Irreversible at the row level**: lifted rows live in
         * ARCHIVE_LOCAL_STATE going forward. The legacy tables remain as
         * read-only fallback through L1-3 and are dropped in L1-4.
         */
        @VisibleForTesting
        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createArchiveLocalStateTable(db)
                liftDownloadsToArchiveLocalState(db)
                liftHistoryToArchiveLocalState(db)
                liftLocalFavoritesToArchiveLocalState(db)
            }
        }

        private fun createArchiveLocalStateTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ARCHIVE_LOCAL_STATE (
                    ARCID TEXT NOT NULL,
                    SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                    ARCHIVE_JSON TEXT NOT NULL,
                    DOWNLOAD_STATE INTEGER,
                    DOWNLOAD_LEGACY INTEGER NOT NULL DEFAULT 0,
                    DOWNLOAD_TIME INTEGER,
                    DOWNLOAD_LABEL TEXT,
                    DOWNLOAD_ARCHIVE_URI TEXT,
                    HISTORY_TIME INTEGER,
                    HISTORY_MODE INTEGER NOT NULL DEFAULT 0,
                    FAVORITE_TIME INTEGER,
                    PRIMARY KEY (ARCID)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_SERVER_PROFILE_ID` ON `ARCHIVE_LOCAL_STATE` (`SERVER_PROFILE_ID`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_DOWNLOAD_TIME` ON `ARCHIVE_LOCAL_STATE` (`DOWNLOAD_TIME`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_HISTORY_TIME` ON `ARCHIVE_LOCAL_STATE` (`HISTORY_TIME`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_FAVORITE_TIME` ON `ARCHIVE_LOCAL_STATE` (`FAVORITE_TIME`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_DOWNLOAD_LABEL` ON `ARCHIVE_LOCAL_STATE` (`DOWNLOAD_LABEL`)")
        }

        /**
         * Migration-time JSON encoder. `ignoreUnknownKeys` keeps lifted
         * rows readable if [Archive] grows new optional fields later;
         * `encodeDefaults = false` keeps stored JSON tight.
         */
        private val MIGRATION_JSON: Json = Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
        }

        /**
         * Build the `ARCHIVE_JSON` payload for a row lifted out of one
         * of the legacy tables. Only fields present in the v22 schema
         * survive — tags is empty (the @Ignore field never made it to
         * disk), and pagecount/progress/extension/filename get sane
         * defaults that the next LRR detail fetch will overwrite.
         */
        private fun encodeArchiveForLift(
            arcid: String,
            title: String,
            thumbnailUrl: String,
            rating: Float,
            serverProfileId: Long,
            lastreadtime: Long,
        ): String {
            val archive = Archive(
                arcid = arcid,
                title = title,
                tags = emptyMap(),
                pagecount = 0,
                progress = 0,
                extension = "",
                filename = "",
                thumbnailUrl = thumbnailUrl,
                rating = rating,
                isnew = false,
                lastreadtime = lastreadtime,
                summary = null,
                serverProfileId = serverProfileId,
            )
            return MIGRATION_JSON.encodeToString(Archive.serializer(), archive)
        }

        @Suppress("NestedBlockDepth")
        private fun liftDownloadsToArchiveLocalState(db: SupportSQLiteDatabase) {
            // First lift: no rows in ARCHIVE_LOCAL_STATE yet → plain INSERT.
            val insertStmt = db.compileStatement(
                "INSERT OR IGNORE INTO ARCHIVE_LOCAL_STATE " +
                    "(ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, DOWNLOAD_STATE, DOWNLOAD_LEGACY, DOWNLOAD_TIME, DOWNLOAD_LABEL, DOWNLOAD_ARCHIVE_URI) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )
            try {
                db.query(
                    "SELECT ARCID, TITLE, THUMB, RATING, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI FROM DOWNLOADS"
                ).use { c ->
                    while (c.moveToNext()) {
                        val arcid = c.getString(0) ?: continue
                        val title = if (c.isNull(1)) "" else c.getString(1)
                        val thumb = if (c.isNull(2)) "" else c.getString(2)
                        val rating = c.getFloat(3)
                        val serverProfileId = c.getLong(4)
                        val state = c.getInt(5)
                        val legacy = c.getInt(6)
                        val time = c.getLong(7)
                        val label = if (c.isNull(8)) null else c.getString(8)
                        val archiveUri = if (c.isNull(9)) null else c.getString(9)

                        val archiveJson = encodeArchiveForLift(
                            arcid = arcid,
                            title = title,
                            thumbnailUrl = thumb,
                            rating = rating,
                            serverProfileId = serverProfileId,
                            lastreadtime = 0L,
                        )

                        insertStmt.clearBindings()
                        insertStmt.bindString(1, arcid)
                        insertStmt.bindLong(2, serverProfileId)
                        insertStmt.bindString(3, archiveJson)
                        insertStmt.bindLong(4, state.toLong())
                        insertStmt.bindLong(5, legacy.toLong())
                        insertStmt.bindLong(6, time)
                        if (label != null) insertStmt.bindString(7, label) else insertStmt.bindNull(7)
                        if (archiveUri != null) insertStmt.bindString(8, archiveUri) else insertStmt.bindNull(8)
                        insertStmt.executeInsert()
                    }
                }
            } finally {
                insertStmt.close()
            }
        }

        @Suppress("NestedBlockDepth")
        private fun liftHistoryToArchiveLocalState(db: SupportSQLiteDatabase) {
            // Idempotent INSERT-then-UPDATE pattern (see migration KDoc).
            val insertIgnoreStmt = db.compileStatement(
                "INSERT OR IGNORE INTO ARCHIVE_LOCAL_STATE " +
                    "(ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, HISTORY_TIME, HISTORY_MODE) " +
                    "VALUES (?, ?, ?, ?, ?)"
            )
            val updateStmt = db.compileStatement(
                "UPDATE ARCHIVE_LOCAL_STATE SET HISTORY_TIME = ?, HISTORY_MODE = ? WHERE ARCID = ?"
            )
            try {
                db.query(
                    "SELECT ARCID, TITLE, THUMB, RATING, SERVER_PROFILE_ID, MODE, TIME FROM HISTORY"
                ).use { c ->
                    while (c.moveToNext()) {
                        val arcid = c.getString(0) ?: continue
                        val title = if (c.isNull(1)) "" else c.getString(1)
                        val thumb = if (c.isNull(2)) "" else c.getString(2)
                        val rating = c.getFloat(3)
                        val serverProfileId = c.getLong(4)
                        val mode = c.getInt(5)
                        val time = c.getLong(6)

                        val archiveJson = encodeArchiveForLift(
                            arcid = arcid,
                            title = title,
                            thumbnailUrl = thumb,
                            rating = rating,
                            serverProfileId = serverProfileId,
                            lastreadtime = time,
                        )

                        insertIgnoreStmt.clearBindings()
                        insertIgnoreStmt.bindString(1, arcid)
                        insertIgnoreStmt.bindLong(2, serverProfileId)
                        insertIgnoreStmt.bindString(3, archiveJson)
                        insertIgnoreStmt.bindLong(4, time)
                        insertIgnoreStmt.bindLong(5, mode.toLong())
                        insertIgnoreStmt.executeInsert()

                        updateStmt.clearBindings()
                        updateStmt.bindLong(1, time)
                        updateStmt.bindLong(2, mode.toLong())
                        updateStmt.bindString(3, arcid)
                        updateStmt.executeUpdateDelete()
                    }
                }
            } finally {
                insertIgnoreStmt.close()
                updateStmt.close()
            }
        }

        @Suppress("NestedBlockDepth")
        private fun liftLocalFavoritesToArchiveLocalState(db: SupportSQLiteDatabase) {
            val insertIgnoreStmt = db.compileStatement(
                "INSERT OR IGNORE INTO ARCHIVE_LOCAL_STATE " +
                    "(ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, FAVORITE_TIME) " +
                    "VALUES (?, ?, ?, ?)"
            )
            val updateStmt = db.compileStatement(
                "UPDATE ARCHIVE_LOCAL_STATE SET FAVORITE_TIME = ? WHERE ARCID = ?"
            )
            try {
                db.query(
                    "SELECT ARCID, TITLE, THUMB, RATING, SERVER_PROFILE_ID, TIME FROM LOCAL_FAVORITES"
                ).use { c ->
                    while (c.moveToNext()) {
                        val arcid = c.getString(0) ?: continue
                        val title = if (c.isNull(1)) "" else c.getString(1)
                        val thumb = if (c.isNull(2)) "" else c.getString(2)
                        val rating = c.getFloat(3)
                        val serverProfileId = c.getLong(4)
                        val time = c.getLong(5)

                        val archiveJson = encodeArchiveForLift(
                            arcid = arcid,
                            title = title,
                            thumbnailUrl = thumb,
                            rating = rating,
                            serverProfileId = serverProfileId,
                            lastreadtime = 0L,
                        )

                        insertIgnoreStmt.clearBindings()
                        insertIgnoreStmt.bindString(1, arcid)
                        insertIgnoreStmt.bindLong(2, serverProfileId)
                        insertIgnoreStmt.bindString(3, archiveJson)
                        insertIgnoreStmt.bindLong(4, time)
                        insertIgnoreStmt.executeInsert()

                        updateStmt.clearBindings()
                        updateStmt.bindLong(1, time)
                        updateStmt.bindString(2, arcid)
                        updateStmt.executeUpdateDelete()
                    }
                }
            } finally {
                insertIgnoreStmt.close()
                updateStmt.close()
            }
        }

        @VisibleForTesting
        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── DOWNLOADS ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS DOWNLOADS_NEW (
                        ARCID TEXT NOT NULL,
                        GID INTEGER NOT NULL DEFAULT 0,
                        TITLE TEXT,
                        TITLE_JPN TEXT,
                        THUMB TEXT,
                        CATEGORY INTEGER NOT NULL DEFAULT 0,
                        POSTED TEXT,
                        UPLOADER TEXT,
                        RATING REAL NOT NULL DEFAULT 0,
                        SIMPLE_LANGUAGE TEXT,
                        SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                        STATE INTEGER NOT NULL DEFAULT 0,
                        LEGACY INTEGER NOT NULL DEFAULT 0,
                        TIME INTEGER NOT NULL DEFAULT 0,
                        LABEL TEXT,
                        ARCHIVE_URI TEXT,
                        PRIMARY KEY (ARCID)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO DOWNLOADS_NEW
                    SELECT ARCID, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI
                    FROM DOWNLOADS WHERE ARCID IS NOT NULL AND ARCID != ''
                """.trimIndent())
                db.execSQL("DROP TABLE DOWNLOADS")
                db.execSQL("ALTER TABLE DOWNLOADS_NEW RENAME TO DOWNLOADS")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_SERVER_PROFILE_ID` ON `DOWNLOADS` (`SERVER_PROFILE_ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_TIME` ON `DOWNLOADS` (`TIME`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_LABEL` ON `DOWNLOADS` (`LABEL`)")

                // ── HISTORY ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS HISTORY_NEW (
                        ARCID TEXT NOT NULL,
                        GID INTEGER NOT NULL DEFAULT 0,
                        TITLE TEXT,
                        TITLE_JPN TEXT,
                        THUMB TEXT,
                        CATEGORY INTEGER NOT NULL DEFAULT 0,
                        POSTED TEXT,
                        UPLOADER TEXT,
                        RATING REAL NOT NULL DEFAULT 0,
                        SIMPLE_LANGUAGE TEXT,
                        SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                        MODE INTEGER NOT NULL DEFAULT 0,
                        TIME INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (ARCID)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO HISTORY_NEW
                    SELECT ARCID, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, MODE, TIME
                    FROM HISTORY WHERE ARCID IS NOT NULL AND ARCID != ''
                """.trimIndent())
                db.execSQL("DROP TABLE HISTORY")
                db.execSQL("ALTER TABLE HISTORY_NEW RENAME TO HISTORY")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_HISTORY_SERVER_PROFILE_ID` ON `HISTORY` (`SERVER_PROFILE_ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_HISTORY_TIME` ON `HISTORY` (`TIME`)")

                // ── LOCAL_FAVORITES ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS LOCAL_FAVORITES_NEW (
                        ARCID TEXT NOT NULL,
                        GID INTEGER NOT NULL DEFAULT 0,
                        TITLE TEXT,
                        TITLE_JPN TEXT,
                        THUMB TEXT,
                        CATEGORY INTEGER NOT NULL DEFAULT 0,
                        POSTED TEXT,
                        UPLOADER TEXT,
                        RATING REAL NOT NULL DEFAULT 0,
                        SIMPLE_LANGUAGE TEXT,
                        SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                        TIME INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (ARCID)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO LOCAL_FAVORITES_NEW
                    SELECT ARCID, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, TIME
                    FROM LOCAL_FAVORITES WHERE ARCID IS NOT NULL AND ARCID != ''
                """.trimIndent())
                db.execSQL("DROP TABLE LOCAL_FAVORITES")
                db.execSQL("ALTER TABLE LOCAL_FAVORITES_NEW RENAME TO LOCAL_FAVORITES")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_LOCAL_FAVORITES_TIME` ON `LOCAL_FAVORITES` (`TIME`)")
            }
        }

        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── DOWNLOADS ──
                db.execSQL("""
                    CREATE TABLE DOWNLOADS_NEW (
                        ARCID TEXT NOT NULL,
                        GID INTEGER NOT NULL,
                        TITLE TEXT,
                        TITLE_JPN TEXT,
                        THUMB TEXT,
                        CATEGORY INTEGER NOT NULL DEFAULT 0,
                        POSTED TEXT,
                        UPLOADER TEXT,
                        RATING REAL NOT NULL DEFAULT 0,
                        SIMPLE_LANGUAGE TEXT,
                        SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                        STATE INTEGER NOT NULL DEFAULT 0,
                        LEGACY INTEGER NOT NULL DEFAULT 0,
                        TIME INTEGER NOT NULL DEFAULT 0,
                        LABEL TEXT,
                        ARCHIVE_URI TEXT,
                        PRIMARY KEY (ARCID)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO DOWNLOADS_NEW (ARCID, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI)
                    SELECT TOKEN, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI
                    FROM DOWNLOADS WHERE TOKEN IS NOT NULL AND TOKEN != ''
                """.trimIndent())
                db.execSQL("DROP TABLE DOWNLOADS")
                db.execSQL("ALTER TABLE DOWNLOADS_NEW RENAME TO DOWNLOADS")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_SERVER_PROFILE_ID` ON `DOWNLOADS` (`SERVER_PROFILE_ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_TIME` ON `DOWNLOADS` (`TIME`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_LABEL` ON `DOWNLOADS` (`LABEL`)")

                // ── HISTORY ──
                db.execSQL("""
                    CREATE TABLE HISTORY_NEW (
                        ARCID TEXT NOT NULL,
                        GID INTEGER NOT NULL,
                        TITLE TEXT,
                        TITLE_JPN TEXT,
                        THUMB TEXT,
                        CATEGORY INTEGER NOT NULL DEFAULT 0,
                        POSTED TEXT,
                        UPLOADER TEXT,
                        RATING REAL NOT NULL DEFAULT 0,
                        SIMPLE_LANGUAGE TEXT,
                        SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                        MODE INTEGER NOT NULL DEFAULT 0,
                        TIME INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (ARCID)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO HISTORY_NEW (ARCID, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, MODE, TIME)
                    SELECT TOKEN, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, MODE, TIME
                    FROM HISTORY WHERE TOKEN IS NOT NULL AND TOKEN != ''
                """.trimIndent())
                db.execSQL("DROP TABLE HISTORY")
                db.execSQL("ALTER TABLE HISTORY_NEW RENAME TO HISTORY")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_HISTORY_SERVER_PROFILE_ID` ON `HISTORY` (`SERVER_PROFILE_ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_HISTORY_TIME` ON `HISTORY` (`TIME`)")

                // ── LOCAL_FAVORITES ──
                db.execSQL("""
                    CREATE TABLE LOCAL_FAVORITES_NEW (
                        ARCID TEXT NOT NULL,
                        GID INTEGER NOT NULL,
                        TITLE TEXT,
                        TITLE_JPN TEXT,
                        THUMB TEXT,
                        CATEGORY INTEGER NOT NULL DEFAULT 0,
                        POSTED TEXT,
                        UPLOADER TEXT,
                        RATING REAL NOT NULL DEFAULT 0,
                        SIMPLE_LANGUAGE TEXT,
                        SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                        TIME INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (ARCID)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO LOCAL_FAVORITES_NEW (ARCID, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, TIME)
                    SELECT TOKEN, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, TIME
                    FROM LOCAL_FAVORITES WHERE TOKEN IS NOT NULL AND TOKEN != ''
                """.trimIndent())
                db.execSQL("DROP TABLE LOCAL_FAVORITES")
                db.execSQL("ALTER TABLE LOCAL_FAVORITES_NEW RENAME TO LOCAL_FAVORITES")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_LOCAL_FAVORITES_TIME` ON `LOCAL_FAVORITES` (`TIME`)")

                // ── DOWNLOAD_DIRNAME ──
                db.execSQL("""
                    CREATE TABLE DOWNLOAD_DIRNAME_NEW (
                        ARCID TEXT NOT NULL,
                        DIRNAME TEXT,
                        PRIMARY KEY (ARCID)
                    )
                """.trimIndent())
                // Join with DOWNLOADS to get ARCID from the GID foreign key
                db.execSQL("""
                    INSERT INTO DOWNLOAD_DIRNAME_NEW (ARCID, DIRNAME)
                    SELECT d.ARCID, dd.DIRNAME
                    FROM DOWNLOAD_DIRNAME dd
                    INNER JOIN DOWNLOADS d ON dd.GID = d.GID
                """.trimIndent())
                db.execSQL("DROP TABLE DOWNLOAD_DIRNAME")
                db.execSQL("ALTER TABLE DOWNLOAD_DIRNAME_NEW RENAME TO DOWNLOAD_DIRNAME")
            }
        }

        /**
         * v16 → v17: Drop the BOOKMARKS table.
         *
         * BookmarkInfo was an EhViewer-era per-gallery "reader bookmark" entity
         * (remembering which page you were on in a gallery). It had zero callers
         * in the LR Reader codebase — no UI scene, no EhDB wrapper methods,
         * not even a single insert/query from anywhere outside the DAO. The
         * DAO methods existed but were never invoked; the BOOKMARKS table
         * has been silently inert for as long as the LRR conversion has
         * existed.
         *
         * Drops cleanly — no FK references and the only indexed column is
         * the primary key which vanishes with the table.
         */
        @VisibleForTesting
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS BOOKMARKS")
            }
        }

        /**
         * v15 → v16: Drop the Black_List table.
         *
         * The BlackList subsystem (an EhViewer-era "bad uploader" personal
         * blocklist) was deleted as a follow-up to C2 (EhFilter removal).
         * BlackListActivity was unreachable from anywhere in the app — no
         * Intent, no menu item, no preference — so the data was already
         * orphaned in the sense that no UI could reach it. The Activity,
         * Room entity, DAO methods, layouts, strings, ids and AndroidManifest
         * declaration were all removed in the same change as this migration.
         *
         * On upgrade we drop the table — there were no FK references and
         * the only index (BADGAYNAME) goes away with the table.
         */
        @VisibleForTesting
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS Black_List")
            }
        }

        /**
         * v14 → v15: Drop the FILTER table.
         *
         * The EhFilter subsystem (user-defined title/uploader/tag blacklist) was a
         * holdover from EhViewer's public-site model. LR Reader is a private library
         * client where users curate stored content directly, so an in-app blacklist
         * has no use case. The class, DAO, entity and UI entry points were removed
         * in the same change as this migration.
         *
         * On upgrade we simply drop the table — any user-configured filters were
         * already dead data because the consumption path had been severed during
         * the EhViewer→LRR conversion (see W1-3 落地说明 in
         * docs/audit-2026-04-06.md). No data worth preserving.
         */
        @VisibleForTesting
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS FILTER")
            }
        }

        /**
         * v13 → v14: Add ALLOW_CLEARTEXT column to SERVER_PROFILES.
         *
         * Per-profile opt-in for plain HTTP. The authoritative cleartext gate
         * (LRRCleartextRejectionInterceptor) reads this flag via LRRAuthManager
         * and rejects HTTP requests when it is false.
         *
         * Default 1 grandfathers existing profiles to "trusted cleartext" so
         * existing HTTP LAN setups continue working after the upgrade.
         */
        @VisibleForTesting
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE SERVER_PROFILES ADD COLUMN ALLOW_CLEARTEXT INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v12 → v13: Add LABEL index on DOWNLOADS table.
         *
         * Supports efficient label-based filtering in the download list.
         */
        @VisibleForTesting
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_LABEL` ON `DOWNLOADS` (`LABEL`)")
            }
        }

        /**
         * v11 → v12: Remove plaintext API_KEY column from SERVER_PROFILES.
         *
         * API keys are now stored exclusively in EncryptedSharedPreferences
         * via LRRAuthManager. The Room column was a security remnant.
         *
         * SQLite < 3.35.0 (Android < API 34) doesn't support DROP COLUMN,
         * so we use the recreate-table pattern for API 28+ compatibility.
         */
        @VisibleForTesting
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE SERVER_PROFILES_NEW (
                        ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        NAME TEXT NOT NULL,
                        URL TEXT NOT NULL,
                        IS_ACTIVE INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO SERVER_PROFILES_NEW (ID, NAME, URL, IS_ACTIVE)
                    SELECT ID, NAME, URL, IS_ACTIVE FROM SERVER_PROFILES
                """.trimIndent())
                db.execSQL("DROP TABLE SERVER_PROFILES")
                db.execSQL("ALTER TABLE SERVER_PROFILES_NEW RENAME TO SERVER_PROFILES")
            }
        }

        /**
         * v9 → v10: Recompute GID column from TOKEN (arcid) using SHA-256 first 8 bytes.
         *
         * The old GID was computed as arcid.hashCode().toLong() & 0x7FFFFFFF (32-bit space,
         * 50% collision at ~77K archives). The new GID uses SHA-256 (63-bit space, negligible
         * collision probability). Since TOKEN stores the arcid verbatim, we can recompute
         * without data loss.
         *
         * Tables with (GID, TOKEN): DOWNLOADS, HISTORY, LOCAL_FAVORITES, BOOKMARKS
         * Tables with GID only:     DOWNLOAD_DIRNAME (references DOWNLOADS.GID),
         *                           Gallery_Tags (references gallery GIDs)
         */
        @VisibleForTesting
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_SERVER_PROFILE_ID` ON `DOWNLOADS` (`SERVER_PROFILE_ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOADS_TIME` ON `DOWNLOADS` (`TIME`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_HISTORY_SERVER_PROFILE_ID` ON `HISTORY` (`SERVER_PROFILE_ID`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_HISTORY_TIME` ON `HISTORY` (`TIME`)")
            }
        }

        @VisibleForTesting
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- Collect old→new GID mappings from TOKEN-bearing tables ---
                val downloadsMap = collectGidMap(db, "DOWNLOADS")
                val historyMap   = collectGidMap(db, "HISTORY")
                val localFavMap  = collectGidMap(db, "LOCAL_FAVORITES")
                val bookmarksMap = collectGidMap(db, "BOOKMARKS")

                // Combined map for tables that reference GIDs from any source
                val allMap = HashMap<Long, Long>().apply {
                    putAll(downloadsMap); putAll(historyMap)
                    putAll(localFavMap);  putAll(bookmarksMap)
                }

                // --- Update dependent tables BEFORE main tables ---
                // (DOWNLOADS.GID is a PK; update DOWNLOAD_DIRNAME first to avoid losing the ref)
                updateGids(db, "DOWNLOAD_DIRNAME", downloadsMap)
                updateGids(db, "Gallery_Tags", allMap)

                // --- Update main tables ---
                updateGids(db, "DOWNLOADS",       downloadsMap)
                updateGids(db, "HISTORY",         historyMap)
                updateGids(db, "LOCAL_FAVORITES", localFavMap)
                updateGids(db, "BOOKMARKS",       bookmarksMap)
            }

            private fun collectGidMap(db: SupportSQLiteDatabase, table: String): Map<Long, Long> {
                val map = HashMap<Long, Long>()
                db.query("SELECT GID, TOKEN FROM $table WHERE TOKEN IS NOT NULL AND TOKEN != ''").use { c ->
                    while (c.moveToNext()) {
                        val oldGid = c.getLong(0)
                        val arcid  = c.getString(1)
                        val newGid = sha256Gid(arcid)
                        if (oldGid != newGid) map[oldGid] = newGid
                    }
                }
                return map
            }

            private fun updateGids(db: SupportSQLiteDatabase, table: String, map: Map<Long, Long>) {
                if (map.isEmpty()) return
                val stmt = db.compileStatement("UPDATE $table SET GID = ? WHERE GID = ?")
                for ((old, new) in map) {
                    stmt.clearBindings()
                    stmt.bindLong(1, new)
                    stmt.bindLong(2, old)
                    stmt.execute()
                }
                stmt.close()
            }

            private fun sha256Gid(arcid: String): Long {
                val digest = MessageDigest.getInstance("SHA-256").digest(arcid.toByteArray(Charsets.UTF_8))
                return ByteBuffer.wrap(digest, 0, 8).getLong() and Long.MAX_VALUE
            }
        }
    }
}
