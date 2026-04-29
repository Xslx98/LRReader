package com.hippo.ehviewer.dao

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration path test for v24 → v25 (MIGRATION_24_25).
 *
 * Verifies that adding the `HISTORY_SCROLL_FRACTION` column to
 * `ARCHIVE_LOCAL_STATE` is non-destructive (existing rows preserved,
 * new column defaults to NULL) and that the column accepts the
 * expected REAL range.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MIGRATION_24_25_Test {

    private lateinit var db: SupportSQLiteDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) {
            db.close()
        }
    }

    /**
     * Creates a v24-shaped `ARCHIVE_LOCAL_STATE` table only — the
     * other v24 tables aren't relevant to this migration.
     */
    private fun createV24Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                override fun onCreate(db: SupportSQLiteDatabase) {
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
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `ARCHIVE_LOCAL_STATE gains HISTORY_SCROLL_FRACTION column with NULL default`() {
        db = createV24Database()
        // Seed a couple of rows that pre-date the new column.
        db.execSQL(
            "INSERT INTO ARCHIVE_LOCAL_STATE " +
                "(ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, HISTORY_TIME, HISTORY_MODE) " +
                "VALUES ('arc-h', 0, '{\"arcid\":\"arc-h\"}', 1700000000000, 0)"
        )
        db.execSQL(
            "INSERT INTO ARCHIVE_LOCAL_STATE " +
                "(ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, FAVORITE_TIME) " +
                "VALUES ('arc-f', 0, '{\"arcid\":\"arc-f\"}', 1700000001000)"
        )

        AppDatabase.MIGRATION_24_25.migrate(db)

        // Schema check: the column exists.
        val cursor = db.query("PRAGMA table_info(ARCHIVE_LOCAL_STATE)")
        val columns = mutableSetOf<String>()
        cursor.use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                columns.add(c.getString(nameIdx))
            }
        }
        assertTrue(
            "HISTORY_SCROLL_FRACTION should be present after MIGRATION_24_25",
            columns.contains("HISTORY_SCROLL_FRACTION")
        )

        // Existing rows remain readable; new column defaults to NULL.
        db.query(
            "SELECT ARCID, HISTORY_SCROLL_FRACTION FROM ARCHIVE_LOCAL_STATE ORDER BY ARCID"
        ).use { c ->
            assertTrue(c.moveToNext())
            assertEquals("arc-f", c.getString(0))
            assertTrue(c.isNull(1))

            assertTrue(c.moveToNext())
            assertEquals("arc-h", c.getString(0))
            assertTrue(c.isNull(1))

            assertTrue(!c.moveToNext())
        }
    }

    @Test
    fun `HISTORY_SCROLL_FRACTION accepts updates and round-trips REAL values`() {
        db = createV24Database()
        db.execSQL(
            "INSERT INTO ARCHIVE_LOCAL_STATE " +
                "(ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, HISTORY_TIME, HISTORY_MODE) " +
                "VALUES ('arc-1', 0, '{\"arcid\":\"arc-1\"}', 1700000000000, 0)"
        )
        AppDatabase.MIGRATION_24_25.migrate(db)

        db.execSQL(
            "UPDATE ARCHIVE_LOCAL_STATE SET HISTORY_SCROLL_FRACTION = 0.42 WHERE ARCID = 'arc-1'"
        )
        db.query("SELECT HISTORY_SCROLL_FRACTION FROM ARCHIVE_LOCAL_STATE WHERE ARCID = 'arc-1'")
            .use { c ->
                assertTrue(c.moveToNext())
                assertEquals(0.42f, c.getFloat(0), 1e-6f)
            }

        // NULL is a valid value (the absent sentinel).
        db.execSQL(
            "UPDATE ARCHIVE_LOCAL_STATE SET HISTORY_SCROLL_FRACTION = NULL WHERE ARCID = 'arc-1'"
        )
        db.query("SELECT HISTORY_SCROLL_FRACTION FROM ARCHIVE_LOCAL_STATE WHERE ARCID = 'arc-1'")
            .use { c ->
                assertTrue(c.moveToNext())
                assertTrue(c.isNull(0))
            }
    }
}
