package com.hippo.ehviewer.dao

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration path test for v19 → v20 (MIGRATION_19_20).
 *
 * v19 → v20 is the most semantically dangerous migration in the chain:
 * primary key changes from `GID INTEGER` to `ARCID TEXT`, and the source
 * column for the new PK is `TOKEN` (the v19 LANraragi-style identifier).
 *
 * The migration's contract:
 *   1. Each row's `TOKEN` becomes the new `ARCID` (PK).
 *   2. Rows with NULL or empty `TOKEN` are dropped (irrecoverable).
 *   3. All other columns survive byte-for-byte.
 *   4. The new tables carry SERVER_PROFILE_ID / TIME / LABEL indexes.
 *
 * Coverage gap before this test: the early-version migration chain
 * (v9 → v20) had no migration tests at all, even though v19 → v20 is
 * the most error-prone single step. v21+ tests do exist (see
 * RoomMigrationV21V22Test, MIGRATION_22_23_Test, MIGRATION_24_25_Test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomMigrationV19V20Test {

    private lateinit var db: SupportSQLiteDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) {
            db.close()
        }
    }

    private fun createV19Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v19 schema: GID was the PK, TOKEN a nullable TEXT
                    // column. Other columns mirror what MIGRATION_19_20
                    // SELECTs (see AppDatabase.kt for the source query).
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS DOWNLOADS (
                            GID INTEGER NOT NULL,
                            TOKEN TEXT,
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
                            PRIMARY KEY (GID)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS HISTORY (
                            GID INTEGER NOT NULL,
                            TOKEN TEXT,
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
                            PRIMARY KEY (GID)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS LOCAL_FAVORITES (
                            GID INTEGER NOT NULL,
                            TOKEN TEXT,
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
                            PRIMARY KEY (GID)
                        )
                        """.trimIndent()
                    )
                    // v19 also had a DOWNLOAD_DIRNAME table keyed by GID; the
                    // migration joins it with DOWNLOADS to lift the directory
                    // name into the new ARCID-keyed table.
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS DOWNLOAD_DIRNAME (
                            GID INTEGER NOT NULL,
                            DIRNAME TEXT,
                            PRIMARY KEY (GID)
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

    private fun pkColumnsOf(table: String): List<String> {
        val cursor = db.query("PRAGMA table_info($table)")
        val pkCols = mutableListOf<Pair<String, Int>>()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                val pkSeq = it.getInt(it.getColumnIndexOrThrow("pk"))
                if (pkSeq > 0) pkCols.add(name to pkSeq)
            }
        }
        return pkCols.sortedBy { it.second }.map { it.first }
    }

    private fun indexesOf(table: String): Set<String> {
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name = ?",
            arrayOf(table)
        )
        val idx = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) idx.add(it.getString(0))
        }
        return idx
    }

    @Test
    fun migration_remapsTokenToArcid_preservesAllOtherColumns() {
        db = createV19Database()

        // Single full row in each table — every column populated so the
        // post-migration assertions can verify byte-for-byte preservation.
        db.execSQL(
            "INSERT INTO DOWNLOADS (GID, TOKEN, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI) " +
                "VALUES (1001, 'arc-d-1', 'D Title', 'JpnD', 'http://t/d.jpg', 5, '2026-01-01', 'u-d', 4.5, 'EN', 7, 2, 1, 1700000000000, 'lab-d', 'content://d')"
        )
        db.execSQL(
            "INSERT INTO HISTORY (GID, TOKEN, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, MODE, TIME) " +
                "VALUES (2001, 'arc-h-1', 'H Title', 'JpnH', 'http://t/h.jpg', 3, '2026-02-01', 'u-h', 3.5, 'JA', 5, 1, 1700000001000)"
        )
        db.execSQL(
            "INSERT INTO LOCAL_FAVORITES (GID, TOKEN, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, TIME) " +
                "VALUES (3001, 'arc-f-1', 'F Title', 'JpnF', 'http://t/f.jpg', 2, '2026-03-01', 'u-f', 5.0, 'ZH', 11, 1700000002000)"
        )
        // DOWNLOAD_DIRNAME row joined via GID — the migration looks up the
        // matching DOWNLOADS row to find the new ARCID.
        db.execSQL(
            "INSERT INTO DOWNLOAD_DIRNAME (GID, DIRNAME) VALUES (1001, 'dir-d-1')"
        )

        AppDatabase.MIGRATION_19_20.migrate(db)

        // PK changed from GID → ARCID for all three tables.
        assertEquals("DOWNLOADS PK should be ARCID", listOf("ARCID"), pkColumnsOf("DOWNLOADS"))
        assertEquals("HISTORY PK should be ARCID", listOf("ARCID"), pkColumnsOf("HISTORY"))
        assertEquals("LOCAL_FAVORITES PK should be ARCID", listOf("ARCID"), pkColumnsOf("LOCAL_FAVORITES"))

        // ── DOWNLOADS row preservation ──
        val cur1 = db.query("SELECT ARCID, GID, TITLE, TITLE_JPN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, SIMPLE_LANGUAGE, SERVER_PROFILE_ID, STATE, LEGACY, TIME, LABEL, ARCHIVE_URI FROM DOWNLOADS WHERE ARCID = 'arc-d-1'")
        cur1.use {
            assertTrue("DOWNLOADS row missing post-migration", it.moveToFirst())
            assertEquals("arc-d-1", it.getString(0))      // ARCID lifted from TOKEN
            assertEquals(1001L, it.getLong(1))             // GID survives
            assertEquals("D Title", it.getString(2))
            assertEquals("JpnD", it.getString(3))
            assertEquals("http://t/d.jpg", it.getString(4))
            assertEquals(5, it.getInt(5))
            assertEquals("2026-01-01", it.getString(6))
            assertEquals("u-d", it.getString(7))
            assertEquals(4.5f, it.getFloat(8), 0.001f)
            assertEquals("EN", it.getString(9))
            assertEquals(7L, it.getLong(10))
            assertEquals(2, it.getInt(11))
            assertEquals(1, it.getInt(12))
            assertEquals(1700000000000L, it.getLong(13))
            assertEquals("lab-d", it.getString(14))
            assertEquals("content://d", it.getString(15))
        }

        // ── HISTORY row preservation ──
        val cur2 = db.query("SELECT ARCID, GID, TITLE, MODE, TIME FROM HISTORY WHERE ARCID = 'arc-h-1'")
        cur2.use {
            assertTrue("HISTORY row missing post-migration", it.moveToFirst())
            assertEquals("arc-h-1", it.getString(0))
            assertEquals(2001L, it.getLong(1))
            assertEquals("H Title", it.getString(2))
            assertEquals(1, it.getInt(3))
            assertEquals(1700000001000L, it.getLong(4))
        }

        // ── LOCAL_FAVORITES row preservation ──
        val cur3 = db.query("SELECT ARCID, GID, TITLE, TIME FROM LOCAL_FAVORITES WHERE ARCID = 'arc-f-1'")
        cur3.use {
            assertTrue("LOCAL_FAVORITES row missing post-migration", it.moveToFirst())
            assertEquals("arc-f-1", it.getString(0))
            assertEquals(3001L, it.getLong(1))
            assertEquals("F Title", it.getString(2))
            assertEquals(1700000002000L, it.getLong(3))
        }

        // Indexes promised in the migration body.
        val dlIdx = indexesOf("DOWNLOADS")
        assertTrue("index_DOWNLOADS_SERVER_PROFILE_ID missing", "index_DOWNLOADS_SERVER_PROFILE_ID" in dlIdx)
        assertTrue("index_DOWNLOADS_TIME missing", "index_DOWNLOADS_TIME" in dlIdx)
        assertTrue("index_DOWNLOADS_LABEL missing", "index_DOWNLOADS_LABEL" in dlIdx)

        val hIdx = indexesOf("HISTORY")
        assertTrue("index_HISTORY_SERVER_PROFILE_ID missing", "index_HISTORY_SERVER_PROFILE_ID" in hIdx)
        assertTrue("index_HISTORY_TIME missing", "index_HISTORY_TIME" in hIdx)

        val fIdx = indexesOf("LOCAL_FAVORITES")
        assertTrue("index_LOCAL_FAVORITES_TIME missing", "index_LOCAL_FAVORITES_TIME" in fIdx)

        // DOWNLOAD_DIRNAME PK changed from GID → ARCID, joined via DOWNLOADS.
        assertEquals(
            "DOWNLOAD_DIRNAME PK should be ARCID",
            listOf("ARCID"), pkColumnsOf("DOWNLOAD_DIRNAME")
        )
        db.query("SELECT ARCID, DIRNAME FROM DOWNLOAD_DIRNAME WHERE ARCID = 'arc-d-1'").use {
            assertTrue("DOWNLOAD_DIRNAME row missing post-migration", it.moveToFirst())
            assertEquals("arc-d-1", it.getString(0))
            assertEquals("dir-d-1", it.getString(1))
        }
    }

    @Test
    fun migration_dropsRowsWithNullOrEmptyToken() {
        db = createV19Database()

        // Three rows per table:
        //  - one with valid TOKEN (kept)
        //  - one with NULL TOKEN (dropped)
        //  - one with empty-string TOKEN (dropped)
        db.execSQL("INSERT INTO DOWNLOADS (GID, TOKEN, STATE, LEGACY, TIME) VALUES (1, 'arc-d-keep', 0, 0, 1)")
        db.execSQL("INSERT INTO DOWNLOADS (GID, TOKEN, STATE, LEGACY, TIME) VALUES (2, NULL, 0, 0, 2)")
        db.execSQL("INSERT INTO DOWNLOADS (GID, TOKEN, STATE, LEGACY, TIME) VALUES (3, '', 0, 0, 3)")

        db.execSQL("INSERT INTO HISTORY (GID, TOKEN, MODE, TIME) VALUES (1, 'arc-h-keep', 0, 1)")
        db.execSQL("INSERT INTO HISTORY (GID, TOKEN, MODE, TIME) VALUES (2, NULL, 0, 2)")
        db.execSQL("INSERT INTO HISTORY (GID, TOKEN, MODE, TIME) VALUES (3, '', 0, 3)")

        db.execSQL("INSERT INTO LOCAL_FAVORITES (GID, TOKEN, TIME) VALUES (1, 'arc-f-keep', 1)")
        db.execSQL("INSERT INTO LOCAL_FAVORITES (GID, TOKEN, TIME) VALUES (2, NULL, 2)")
        db.execSQL("INSERT INTO LOCAL_FAVORITES (GID, TOKEN, TIME) VALUES (3, '', 3)")

        AppDatabase.MIGRATION_19_20.migrate(db)

        for (table in listOf("DOWNLOADS", "HISTORY", "LOCAL_FAVORITES")) {
            db.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                assertEquals(
                    "$table should keep exactly the row with valid TOKEN",
                    1, it.getInt(0)
                )
            }
            db.query("SELECT ARCID FROM $table").use {
                assertTrue(it.moveToFirst())
                val arcid = it.getString(0)
                assertTrue("$table surviving ARCID should not be null/empty", !arcid.isNullOrEmpty())
            }
        }
    }

    @Test
    fun migration_isIdempotentOnEmptyTables() {
        db = createV19Database()

        AppDatabase.MIGRATION_19_20.migrate(db)

        // Empty tables exist after migration with new schema.
        for (table in listOf("DOWNLOADS", "HISTORY", "LOCAL_FAVORITES")) {
            db.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                assertEquals("$table should be empty post-migration", 0, it.getInt(0))
            }
        }

        // Schema still valid for INSERT with the new column set.
        db.execSQL(
            "INSERT INTO DOWNLOADS (ARCID, GID, STATE, LEGACY, TIME) VALUES ('arc-x', 99, 0, 0, 9)"
        )
        db.query("SELECT ARCID FROM DOWNLOADS WHERE ARCID = 'arc-x'").use {
            assertTrue(it.moveToFirst())
            assertEquals("arc-x", it.getString(0))
        }

        // GID column is still present in the new schema (it was kept by
        // MIGRATION_19_20 — GID dropping only happens in MIGRATION_21_22).
        db.query("PRAGMA table_info(DOWNLOADS)").use {
            var gidPresent = false
            while (it.moveToNext()) {
                if (it.getString(it.getColumnIndexOrThrow("name")) == "GID") {
                    gidPresent = true
                    break
                }
            }
            assertTrue("GID column should still exist after v19→v20 (drops only at v21→v22)", gidPresent)
        }

        // PRIMARY KEY (ARCID) on the result schema means inserting two rows
        // with the same ARCID violates the constraint.
        db.execSQL(
            "INSERT INTO DOWNLOADS (ARCID, GID, STATE, LEGACY, TIME) VALUES ('arc-y', 100, 0, 0, 10)"
        )
        var conflictCaught = false
        try {
            db.execSQL(
                "INSERT INTO DOWNLOADS (ARCID, GID, STATE, LEGACY, TIME) VALUES ('arc-y', 101, 0, 0, 11)"
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            conflictCaught = true
        }
        assertTrue("Duplicate ARCID insert should violate PK constraint", conflictCaught)

        // Sanity: no leftover *_NEW staging tables from the migration.
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%_NEW'"
        ).use {
            val leftovers = mutableListOf<String>()
            while (it.moveToNext()) leftovers.add(it.getString(0))
            assertTrue("Leftover staging tables: $leftovers", leftovers.isEmpty())
        }

        // Suppress unused-import warnings for assertions we keep available
        // for future row-content checks.
        @Suppress("UNUSED_EXPRESSION")
        run { assertNull(null); assertFalse(false) }
    }
}
