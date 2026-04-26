package com.hippo.ehviewer.dao

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration path test for v21 -> v22 (MIGRATION_21_22).
 *
 * Verifies that the DROP COLUMN dance applied to DOWNLOADS / HISTORY /
 * LOCAL_FAVORITES preserves data in the kept columns and physically
 * removes the dropped EH-era columns (GID / TITLE_JPN / CATEGORY /
 * POSTED / UPLOADER).
 *
 * This migration is irreversible on user devices once shipped, so the
 * data-preservation assertions here are load-bearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomMigrationV21V22Test {

    private lateinit var db: SupportSQLiteDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) {
            db.close()
        }
    }

    private fun createV21Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS DOWNLOADS (
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
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS HISTORY (
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
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS LOCAL_FAVORITES (
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
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) {
                    // unused
                }
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        return helper.writableDatabase
    }

    private fun columnsOf(table: String): Set<String> {
        val cursor = db.query("PRAGMA table_info($table)")
        val cols = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                cols.add(it.getString(it.getColumnIndexOrThrow("name")))
            }
        }
        return cols
    }

    private fun indexesOf(table: String): Set<String> {
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name = ?",
            arrayOf(table)
        )
        val idx = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                idx.add(it.getString(0))
            }
        }
        return idx
    }

    @Test
    fun migration_dropsDeadColumns_preservesKeptColumns_andData() {
        db = createV21Database()

        // Seed each table with one realistic row carrying values in BOTH
        // dropped and kept columns. The assertions below ensure the kept
        // columns survive and the dropped ones disappear.
        db.execSQL(
            "INSERT INTO DOWNLOADS (ARCID, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI) " +
                "VALUES ('arc-d-1', 1001, 'D Title', 'JpnD', 'http://t/d.jpg', 5, '2026-01-01', 'u-d', 4.5, 'EN', 7, 2, 1, 1700000000000, 'lab-d', 'content://d')"
        )
        db.execSQL(
            "INSERT INTO HISTORY (ARCID, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, MODE, TIME) " +
                "VALUES ('arc-h-1', 2001, 'H Title', 'JpnH', 'http://t/h.jpg', 3, '2026-02-01', 'u-h', 3.5, 'JA', 5, 1, 1700000001000)"
        )
        db.execSQL(
            "INSERT INTO LOCAL_FAVORITES (ARCID, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, TIME) " +
                "VALUES ('arc-f-1', 3001, 'F Title', 'JpnF', 'http://t/f.jpg', 2, '2026-03-01', 'u-f', 5.0, 'ZH', 11, 1700000002000)"
        )

        // Run the actual production migration.
        AppDatabase.MIGRATION_21_22.migrate(db)

        // ── DOWNLOADS ──
        val dlCols = columnsOf("DOWNLOADS")
        val keptDl = setOf(
            "ARCID", "TITLE", "THUMB", "RATING", "SIMPLE_LANGUAGE",
            "SERVER_PROFILE_ID", "STATE", "LEGACY", "TIME", "LABEL", "ARCHIVE_URI"
        )
        val droppedDl = setOf("GID", "TITLE_JPN", "CATEGORY", "POSTED", "UPLOADER")
        for (c in keptDl) {
            assertTrue("DOWNLOADS missing kept column $c", c in dlCols)
        }
        for (c in droppedDl) {
            assertFalse("DOWNLOADS still has dropped column $c", c in dlCols)
        }
        val cur1 = db.query("SELECT ARCID, TITLE, THUMB, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI FROM DOWNLOADS WHERE ARCID = 'arc-d-1'")
        cur1.use {
            assertTrue("DOWNLOADS row missing post-migration", it.moveToFirst())
            assertEquals("arc-d-1", it.getString(0))
            assertEquals("D Title", it.getString(1))
            assertEquals("http://t/d.jpg", it.getString(2))
            assertEquals(4.5f, it.getFloat(3), 0.001f)
            assertEquals("EN", it.getString(4))
            assertEquals(7L, it.getLong(5))
            assertEquals(2, it.getInt(6))
            assertEquals(1, it.getInt(7))
            assertEquals(1700000000000L, it.getLong(8))
            assertEquals("lab-d", it.getString(9))
            assertEquals("content://d", it.getString(10))
        }
        val dlIdx = indexesOf("DOWNLOADS")
        assertTrue("index_DOWNLOADS_SERVER_PROFILE_ID missing", "index_DOWNLOADS_SERVER_PROFILE_ID" in dlIdx)
        assertTrue("index_DOWNLOADS_TIME missing", "index_DOWNLOADS_TIME" in dlIdx)
        assertTrue("index_DOWNLOADS_LABEL missing", "index_DOWNLOADS_LABEL" in dlIdx)

        // ── HISTORY ──
        val hCols = columnsOf("HISTORY")
        val keptH = setOf(
            "ARCID", "TITLE", "THUMB", "RATING", "SIMPLE_LANGUAGE",
            "SERVER_PROFILE_ID", "MODE", "TIME"
        )
        for (c in keptH) {
            assertTrue("HISTORY missing kept column $c", c in hCols)
        }
        for (c in droppedDl) {
            assertFalse("HISTORY still has dropped column $c", c in hCols)
        }
        val cur2 = db.query("SELECT ARCID, TITLE, THUMB, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, MODE, TIME FROM HISTORY WHERE ARCID = 'arc-h-1'")
        cur2.use {
            assertTrue("HISTORY row missing post-migration", it.moveToFirst())
            assertEquals("arc-h-1", it.getString(0))
            assertEquals("H Title", it.getString(1))
            assertEquals("http://t/h.jpg", it.getString(2))
            assertEquals(3.5f, it.getFloat(3), 0.001f)
            assertEquals("JA", it.getString(4))
            assertEquals(5L, it.getLong(5))
            assertEquals(1, it.getInt(6))
            assertEquals(1700000001000L, it.getLong(7))
        }
        val hIdx = indexesOf("HISTORY")
        assertTrue("index_HISTORY_SERVER_PROFILE_ID missing", "index_HISTORY_SERVER_PROFILE_ID" in hIdx)
        assertTrue("index_HISTORY_TIME missing", "index_HISTORY_TIME" in hIdx)

        // ── LOCAL_FAVORITES ──
        val fCols = columnsOf("LOCAL_FAVORITES")
        val keptF = setOf(
            "ARCID", "TITLE", "THUMB", "RATING", "SIMPLE_LANGUAGE",
            "SERVER_PROFILE_ID", "TIME"
        )
        for (c in keptF) {
            assertTrue("LOCAL_FAVORITES missing kept column $c", c in fCols)
        }
        for (c in droppedDl) {
            assertFalse("LOCAL_FAVORITES still has dropped column $c", c in fCols)
        }
        val cur3 = db.query("SELECT ARCID, TITLE, THUMB, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, TIME FROM LOCAL_FAVORITES WHERE ARCID = 'arc-f-1'")
        cur3.use {
            assertTrue("LOCAL_FAVORITES row missing post-migration", it.moveToFirst())
            assertEquals("arc-f-1", it.getString(0))
            assertEquals("F Title", it.getString(1))
            assertEquals("http://t/f.jpg", it.getString(2))
            assertEquals(5.0f, it.getFloat(3), 0.001f)
            assertEquals("ZH", it.getString(4))
            assertEquals(11L, it.getLong(5))
            assertEquals(1700000002000L, it.getLong(6))
        }
        val fIdx = indexesOf("LOCAL_FAVORITES")
        assertTrue("index_LOCAL_FAVORITES_TIME missing", "index_LOCAL_FAVORITES_TIME" in fIdx)
    }

    @Test
    fun migration_preservesRowCounts_acrossTables() {
        db = createV21Database()

        // Insert 5 rows per table to confirm bulk preservation.
        repeat(5) { i ->
            db.execSQL(
                "INSERT INTO DOWNLOADS (ARCID, GID, STATE, LEGACY, TIME, CATEGORY, RATING) " +
                    "VALUES ('arc-d-$i', $i, 0, 0, ${1700000000000L + i}, 0, 0.0)"
            )
            db.execSQL(
                "INSERT INTO HISTORY (ARCID, GID, MODE, TIME, CATEGORY, RATING) " +
                    "VALUES ('arc-h-$i', $i, 0, ${1700000000000L + i}, 0, 0.0)"
            )
            db.execSQL(
                "INSERT INTO LOCAL_FAVORITES (ARCID, GID, TIME, CATEGORY, RATING) " +
                    "VALUES ('arc-f-$i', $i, ${1700000000000L + i}, 0, 0.0)"
            )
        }

        AppDatabase.MIGRATION_21_22.migrate(db)

        for ((table, expected) in listOf("DOWNLOADS" to 5, "HISTORY" to 5, "LOCAL_FAVORITES" to 5)) {
            val cur = db.query("SELECT COUNT(*) FROM $table")
            cur.use {
                assertTrue(it.moveToFirst())
                assertEquals("$table row count drift", expected, it.getInt(0))
            }
        }
    }
}
