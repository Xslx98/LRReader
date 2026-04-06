package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.annotation.VisibleForTesting
import java.nio.ByteBuffer
import java.security.MessageDigest

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
        Filter::class,
        BlackList::class,
        GalleryTags::class,
        BookmarkInfo::class,
        ServerProfile::class
    ],
    version = 12,
    exportSchema = true
)
@TypeConverters(DateConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadRoomDao
    abstract fun browsingDao(): BrowsingRoomDao
    abstract fun miscDao(): MiscRoomDao

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
                    .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build()
                    .also { INSTANCE = it }
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
                        val token  = c.getString(1)
                        val newGid = sha256Gid(token)
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
