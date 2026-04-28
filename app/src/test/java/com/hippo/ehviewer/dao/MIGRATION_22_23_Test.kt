package com.hippo.ehviewer.dao

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.domain.Archive
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

/**
 * Migration path test for v22 → v23 (MIGRATION_22_23).
 *
 * Verifies that the data lift from the three legacy tables
 * (`DOWNLOADS`, `HISTORY`, `LOCAL_FAVORITES`) into
 * `ARCHIVE_LOCAL_STATE` preserves every meaningful field on every
 * scenario, that idempotent INSERT-OR-IGNORE-then-UPDATE merges rows
 * with overlapping ARCIDs correctly, and that the operation completes
 * within budget on a realistic dataset.
 *
 * The legacy tables are intentionally kept around in the v23 schema
 * (deferral noted in the migration's KDoc) — assertions here are
 * scoped to ARCHIVE_LOCAL_STATE state, not to whether legacy tables
 * survived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MIGRATION_22_23_Test {

    private lateinit var db: SupportSQLiteDatabase

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) {
            db.close()
        }
    }

    private fun createV22Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(22) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS DOWNLOADS (
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
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS HISTORY (
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
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS LOCAL_FAVORITES (
                            ARCID TEXT NOT NULL,
                            TITLE TEXT,
                            THUMB TEXT,
                            RATING REAL NOT NULL DEFAULT 0,
                            SIMPLE_LANGUAGE TEXT,
                            SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                            TIME INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY (ARCID)
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // unused
                }
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private data class LiftedRow(
        val arcid: String,
        val serverProfileId: Long,
        val archiveJson: String,
        val downloadState: Int?,
        val downloadLegacy: Int,
        val downloadTime: Long?,
        val downloadLabel: String?,
        val downloadArchiveUri: String?,
        val historyTime: Long?,
        val historyMode: Int,
        val favoriteTime: Long?,
    )

    private fun queryRow(arcid: String): LiftedRow? {
        db.query(
            "SELECT ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, DOWNLOAD_STATE, DOWNLOAD_LEGACY, " +
                "DOWNLOAD_TIME, DOWNLOAD_LABEL, DOWNLOAD_ARCHIVE_URI, HISTORY_TIME, HISTORY_MODE, " +
                "FAVORITE_TIME FROM ARCHIVE_LOCAL_STATE WHERE ARCID = ?",
            arrayOf(arcid)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return LiftedRow(
                arcid = c.getString(0),
                serverProfileId = c.getLong(1),
                archiveJson = c.getString(2),
                downloadState = if (c.isNull(3)) null else c.getInt(3),
                downloadLegacy = c.getInt(4),
                downloadTime = if (c.isNull(5)) null else c.getLong(5),
                downloadLabel = if (c.isNull(6)) null else c.getString(6),
                downloadArchiveUri = if (c.isNull(7)) null else c.getString(7),
                historyTime = if (c.isNull(8)) null else c.getLong(8),
                historyMode = c.getInt(9),
                favoriteTime = if (c.isNull(10)) null else c.getLong(10),
            )
        }
    }

    private fun rowCount(): Int {
        db.query("SELECT COUNT(*) FROM ARCHIVE_LOCAL_STATE").use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    private fun decodeArchive(rowJson: String): Archive {
        return json.decodeFromString(Archive.serializer(), rowJson)
    }

    // ──────────────────────────────────────────────────────────
    //  Scenario 1: Only DOWNLOADS has data
    // ──────────────────────────────────────────────────────────

    @Test
    fun migration_liftsDownloadsOnly() {
        db = createV22Database()
        db.execSQL(
            "INSERT INTO DOWNLOADS (ARCID, TITLE, THUMB, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI) " +
                "VALUES ('arc-d-1', 'D Title', 'http://t/d.jpg', 4.5, 'EN', 7, 2, 1, 1700000000000, 'lab-d', 'content://d')"
        )

        AppDatabase.MIGRATION_22_23.migrate(db)

        assertEquals(1, rowCount())
        val row = queryRow("arc-d-1")
        assertNotNull(row)
        row!!
        assertEquals("arc-d-1", row.arcid)
        assertEquals(7L, row.serverProfileId)
        assertEquals(2, row.downloadState)
        assertEquals(1, row.downloadLegacy)
        assertEquals(1700000000000L, row.downloadTime)
        assertEquals("lab-d", row.downloadLabel)
        assertEquals("content://d", row.downloadArchiveUri)
        assertNull(row.historyTime)
        assertEquals(0, row.historyMode)
        assertNull(row.favoriteTime)

        val archive = decodeArchive(row.archiveJson)
        assertEquals("arc-d-1", archive.arcid)
        assertEquals("D Title", archive.title)
        assertEquals("http://t/d.jpg", archive.thumbnailUrl)
        assertEquals(4.5f, archive.rating, 0.001f)
        assertEquals(7L, archive.serverProfileId)
    }

    // ──────────────────────────────────────────────────────────
    //  Scenario 2: Only HISTORY has data
    // ──────────────────────────────────────────────────────────

    @Test
    fun migration_liftsHistoryOnly() {
        db = createV22Database()
        db.execSQL(
            "INSERT INTO HISTORY (ARCID, TITLE, THUMB, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, MODE, TIME) " +
                "VALUES ('arc-h-1', 'H Title', 'http://t/h.jpg', 3.5, 'JA', 5, 1, 1700000001000)"
        )

        AppDatabase.MIGRATION_22_23.migrate(db)

        assertEquals(1, rowCount())
        val row = queryRow("arc-h-1")
        assertNotNull(row)
        row!!
        assertEquals(5L, row.serverProfileId)
        assertNull(row.downloadState)
        assertEquals(0, row.downloadLegacy)
        assertNull(row.downloadTime)
        assertEquals(1700000001000L, row.historyTime)
        assertEquals(1, row.historyMode)
        assertNull(row.favoriteTime)

        val archive = decodeArchive(row.archiveJson)
        assertEquals("arc-h-1", archive.arcid)
        assertEquals("H Title", archive.title)
        assertEquals("http://t/h.jpg", archive.thumbnailUrl)
        assertEquals(3.5f, archive.rating, 0.001f)
        assertEquals(1700000001000L, archive.lastreadtime)
    }

    // ──────────────────────────────────────────────────────────
    //  Scenario 3: Only LOCAL_FAVORITES has data
    // ──────────────────────────────────────────────────────────

    @Test
    fun migration_liftsFavoritesOnly() {
        db = createV22Database()
        db.execSQL(
            "INSERT INTO LOCAL_FAVORITES (ARCID, TITLE, THUMB, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, TIME) " +
                "VALUES ('arc-f-1', 'F Title', 'http://t/f.jpg', 5.0, 'ZH', 11, 1700000002000)"
        )

        AppDatabase.MIGRATION_22_23.migrate(db)

        assertEquals(1, rowCount())
        val row = queryRow("arc-f-1")
        assertNotNull(row)
        row!!
        assertEquals(11L, row.serverProfileId)
        assertNull(row.downloadState)
        assertNull(row.historyTime)
        assertEquals(1700000002000L, row.favoriteTime)

        val archive = decodeArchive(row.archiveJson)
        assertEquals("arc-f-1", archive.arcid)
        assertEquals("F Title", archive.title)
        assertEquals(5.0f, archive.rating, 0.001f)
    }

    // ──────────────────────────────────────────────────────────
    //  Scenario 4: All three tables hold the same arcid → merged
    //              into a single row carrying all three subsystems
    // ──────────────────────────────────────────────────────────

    @Test
    fun migration_mergesSameArcidAcrossThreeTables() {
        db = createV22Database()
        db.execSQL(
            "INSERT INTO DOWNLOADS (ARCID, TITLE, THUMB, RATING, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI) " +
                "VALUES ('arc-merge', 'Merge Title', 'http://t/m.jpg', 4.0, 3, 3, 0, 1700000010000, 'merge-lab', 'content://m')"
        )
        db.execSQL(
            "INSERT INTO HISTORY (ARCID, TITLE, THUMB, RATING, SERVER_PROFILE_ID, MODE, TIME) " +
                "VALUES ('arc-merge', 'Merge Title', 'http://t/m.jpg', 4.0, 3, 2, 1700000020000)"
        )
        db.execSQL(
            "INSERT INTO LOCAL_FAVORITES (ARCID, TITLE, THUMB, RATING, SERVER_PROFILE_ID, TIME) " +
                "VALUES ('arc-merge', 'Merge Title', 'http://t/m.jpg', 4.0, 3, 1700000030000)"
        )

        AppDatabase.MIGRATION_22_23.migrate(db)

        assertEquals("merged into a single row", 1, rowCount())
        val row = queryRow("arc-merge")
        assertNotNull(row)
        row!!
        // Download fields
        assertEquals(3, row.downloadState)
        assertEquals(0, row.downloadLegacy)
        assertEquals(1700000010000L, row.downloadTime)
        assertEquals("merge-lab", row.downloadLabel)
        assertEquals("content://m", row.downloadArchiveUri)
        // History fields
        assertEquals(1700000020000L, row.historyTime)
        assertEquals(2, row.historyMode)
        // Favorite fields
        assertEquals(1700000030000L, row.favoriteTime)
        // archive_json comes from DOWNLOADS (first lifter)
        val archive = decodeArchive(row.archiveJson)
        assertEquals("Merge Title", archive.title)
        // DOWNLOADS lift sets lastreadtime = 0
        assertEquals(0L, archive.lastreadtime)
    }

    // ──────────────────────────────────────────────────────────
    //  Scenario 5: Three tables hold non-overlapping arcids
    // ──────────────────────────────────────────────────────────

    @Test
    fun migration_keepsThreeTablesDisjoint() {
        db = createV22Database()
        db.execSQL(
            "INSERT INTO DOWNLOADS (ARCID, RATING, SERVER_PROFILE_ID, STATE, LEGACY, TIME) " +
                "VALUES ('arc-d-only', 0.0, 1, 0, 0, 1000)"
        )
        db.execSQL(
            "INSERT INTO HISTORY (ARCID, RATING, SERVER_PROFILE_ID, MODE, TIME) " +
                "VALUES ('arc-h-only', 0.0, 1, 0, 2000)"
        )
        db.execSQL(
            "INSERT INTO LOCAL_FAVORITES (ARCID, RATING, SERVER_PROFILE_ID, TIME) " +
                "VALUES ('arc-f-only', 0.0, 1, 3000)"
        )

        AppDatabase.MIGRATION_22_23.migrate(db)

        assertEquals(3, rowCount())

        val rowD = queryRow("arc-d-only")!!
        assertNotNull(rowD.downloadState)
        assertNull(rowD.historyTime)
        assertNull(rowD.favoriteTime)

        val rowH = queryRow("arc-h-only")!!
        assertNull(rowH.downloadState)
        assertEquals(2000L, rowH.historyTime)
        assertNull(rowH.favoriteTime)

        val rowF = queryRow("arc-f-only")!!
        assertNull(rowF.downloadState)
        assertNull(rowF.historyTime)
        assertEquals(3000L, rowF.favoriteTime)
    }

    // ──────────────────────────────────────────────────────────
    //  Scenario 6: All three legacy tables empty
    // ──────────────────────────────────────────────────────────

    @Test
    fun migration_handlesEmptyLegacyTables() {
        db = createV22Database()

        AppDatabase.MIGRATION_22_23.migrate(db)

        assertEquals(0, rowCount())

        // Sanity: ARCHIVE_LOCAL_STATE table itself was created.
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name = 'ARCHIVE_LOCAL_STATE'").use { c ->
            assertTrue("ARCHIVE_LOCAL_STATE table missing post-migration", c.moveToFirst())
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Scenario 7: 1000+ rows perform within < 5s budget
    // ──────────────────────────────────────────────────────────

    @Test
    fun migration_completesWithinBudgetForLargeDataset() {
        db = createV22Database()

        // 600 download-only + 200 history-only + 200 favorite-only = 1000 rows.
        // Plus a 50-row 3-way overlap to exercise the merge path.
        db.beginTransaction()
        try {
            for (i in 0 until 600) {
                db.execSQL(
                    "INSERT INTO DOWNLOADS (ARCID, RATING, SERVER_PROFILE_ID, STATE, LEGACY, TIME) " +
                        "VALUES ('d-$i', 0.0, 0, 0, 0, ${1700000000000L + i})"
                )
            }
            for (i in 0 until 200) {
                db.execSQL(
                    "INSERT INTO HISTORY (ARCID, RATING, SERVER_PROFILE_ID, MODE, TIME) " +
                        "VALUES ('h-$i', 0.0, 0, 0, ${1700000010000L + i})"
                )
            }
            for (i in 0 until 200) {
                db.execSQL(
                    "INSERT INTO LOCAL_FAVORITES (ARCID, RATING, SERVER_PROFILE_ID, TIME) " +
                        "VALUES ('f-$i', 0.0, 0, ${1700000020000L + i})"
                )
            }
            for (i in 0 until 50) {
                db.execSQL(
                    "INSERT INTO DOWNLOADS (ARCID, RATING, SERVER_PROFILE_ID, STATE, LEGACY, TIME) " +
                        "VALUES ('m-$i', 0.0, 0, 0, 0, ${1700000030000L + i})"
                )
                db.execSQL(
                    "INSERT INTO HISTORY (ARCID, RATING, SERVER_PROFILE_ID, MODE, TIME) " +
                        "VALUES ('m-$i', 0.0, 0, 0, ${1700000040000L + i})"
                )
                db.execSQL(
                    "INSERT INTO LOCAL_FAVORITES (ARCID, RATING, SERVER_PROFILE_ID, TIME) " +
                        "VALUES ('m-$i', 0.0, 0, ${1700000050000L + i})"
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val elapsedMs = measureTimeMillis {
            AppDatabase.MIGRATION_22_23.migrate(db)
        }

        assertEquals(1050, rowCount())
        assertTrue("migration took ${elapsedMs}ms — over the 5000ms budget", elapsedMs < 5000)

        // Spot-check one merged row.
        val merged = queryRow("m-25")!!
        assertNotNull(merged.downloadState)
        assertNotNull(merged.historyTime)
        assertNotNull(merged.favoriteTime)
    }

    // ──────────────────────────────────────────────────────────
    //  Idempotence: indexes exist post-migration
    // ──────────────────────────────────────────────────────────

    @Test
    fun migration_createsExpectedIndexes() {
        db = createV22Database()

        AppDatabase.MIGRATION_22_23.migrate(db)

        val indexes = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name = 'ARCHIVE_LOCAL_STATE'"
        ).use { c ->
            while (c.moveToNext()) indexes.add(c.getString(0))
        }
        assertTrue("SERVER_PROFILE_ID index missing", "index_ARCHIVE_LOCAL_STATE_SERVER_PROFILE_ID" in indexes)
        assertTrue("DOWNLOAD_TIME index missing", "index_ARCHIVE_LOCAL_STATE_DOWNLOAD_TIME" in indexes)
        assertTrue("HISTORY_TIME index missing", "index_ARCHIVE_LOCAL_STATE_HISTORY_TIME" in indexes)
        assertTrue("FAVORITE_TIME index missing", "index_ARCHIVE_LOCAL_STATE_FAVORITE_TIME" in indexes)
        assertTrue("DOWNLOAD_LABEL index missing", "index_ARCHIVE_LOCAL_STATE_DOWNLOAD_LABEL" in indexes)
    }

    // ──────────────────────────────────────────────────────────
    //  Sanity: non-target tables are untouched (the L1-2 stage
    //  keeps legacy tables alive — DROP is deferred to L1-4).
    // ──────────────────────────────────────────────────────────

    @Test
    fun migration_leavesLegacyTablesAlive_untilL1Four() {
        db = createV22Database()
        db.execSQL("INSERT INTO DOWNLOADS (ARCID, RATING, STATE, LEGACY, TIME) VALUES ('d-1', 0.0, 0, 0, 1)")

        AppDatabase.MIGRATION_22_23.migrate(db)

        // DOWNLOADS row is still present
        db.query("SELECT COUNT(*) FROM DOWNLOADS").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        // HISTORY / LOCAL_FAVORITES tables are still queryable
        db.query("SELECT COUNT(*) FROM HISTORY").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM LOCAL_FAVORITES").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
    }

}
