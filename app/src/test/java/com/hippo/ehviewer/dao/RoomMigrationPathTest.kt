package com.hippo.ehviewer.dao

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Migration path tests for AppDatabase v9 -> v10 -> v11 -> v12 -> v13 -> v14.
 *
 * These tests exercise the actual migration SQL by:
 * 1. Creating a database at the source version schema
 * 2. Inserting test data
 * 3. Running the migration object's migrate() method
 * 4. Verifying data integrity and schema changes
 *
 * Unlike RoomMigrationTest (which validates the current v14 schema),
 * these tests verify each migration step preserves data correctly.
 *
 * Run with: ./gradlew testAppReleaseDebugUnitTest --tests "*.RoomMigrationPathTest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomMigrationPathTest {

    private lateinit var db: SupportSQLiteDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) {
            db.close()
        }
    }

    // ========== Helper: create in-memory SupportSQLiteDatabase ==========

    /**
     * Creates an in-memory SupportSQLiteDatabase with the given version
     * and runs [onCreate] to set up the schema.
     */
    private fun createDatabase(version: Int, onCreate: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    onCreate(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // not used -- we call migrate() manually
                }
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        return helper.writableDatabase
    }

    // ========== Schema creation helpers (from schema JSON exports) ==========

    /**
     * Creates all tables as they existed in schema v9.
     * v9 and v10 have identical table structures (v9->v10 is a data migration only).
     */
    private fun createV9Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS DOWNLOADS (
                STATE INTEGER NOT NULL, LEGACY INTEGER NOT NULL, TIME INTEGER NOT NULL,
                LABEL TEXT, ARCHIVE_URI TEXT, GID INTEGER NOT NULL, TOKEN TEXT,
                TITLE TEXT, TITLE_JPN TEXT, THUMB TEXT, CATEGORY INTEGER NOT NULL,
                POSTED TEXT, UPLOADER TEXT, RATING REAL NOT NULL, SIMPLE_LANGUAGE TEXT,
                SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS DOWNLOAD_LABELS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT, LABEL TEXT, TIME INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS DOWNLOAD_DIRNAME (
                GID INTEGER NOT NULL, DIRNAME TEXT, PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS HISTORY (
                MODE INTEGER NOT NULL, TIME INTEGER NOT NULL, GID INTEGER NOT NULL,
                TOKEN TEXT, TITLE TEXT, TITLE_JPN TEXT, THUMB TEXT,
                CATEGORY INTEGER NOT NULL, POSTED TEXT, UPLOADER TEXT,
                RATING REAL NOT NULL, SIMPLE_LANGUAGE TEXT,
                SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS LOCAL_FAVORITES (
                TIME INTEGER NOT NULL, GID INTEGER NOT NULL, TOKEN TEXT,
                TITLE TEXT, TITLE_JPN TEXT, THUMB TEXT, CATEGORY INTEGER NOT NULL,
                POSTED TEXT, UPLOADER TEXT, RATING REAL NOT NULL,
                SIMPLE_LANGUAGE TEXT, SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS QUICK_SEARCH (
                _id INTEGER PRIMARY KEY AUTOINCREMENT, NAME TEXT,
                MODE INTEGER NOT NULL, CATEGORY INTEGER NOT NULL, KEYWORD TEXT,
                ADVANCE_SEARCH INTEGER NOT NULL, MIN_RATING INTEGER NOT NULL,
                PAGE_FROM INTEGER NOT NULL, PAGE_TO INTEGER NOT NULL,
                TIME INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS FILTER (
                _id INTEGER PRIMARY KEY AUTOINCREMENT, MODE INTEGER NOT NULL,
                TEXT TEXT, ENABLE INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS Black_List (
                _id INTEGER PRIMARY KEY AUTOINCREMENT, BADGAYNAME TEXT,
                REASON TEXT, ANGRYWITH TEXT, ADD_TIME TEXT, MODE INTEGER)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_Black_List_BADGAYNAME ON Black_List (BADGAYNAME)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS Gallery_Tags (
                GID INTEGER NOT NULL, ROWS TEXT, ARTIST TEXT, COSPLAYER TEXT,
                CHARACTER TEXT, FEMALE TEXT, `GROUP` TEXT, LANGUAGE TEXT,
                MALE TEXT, MISC TEXT, MIXED TEXT, OTHER TEXT, PARODY TEXT,
                RECLASS TEXT, CREATE_TIME INTEGER, UPDATE_TIME INTEGER,
                PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS BOOKMARKS (
                PAGE INTEGER NOT NULL, TIME INTEGER NOT NULL, GID INTEGER NOT NULL,
                TOKEN TEXT, TITLE TEXT, TITLE_JPN TEXT, THUMB TEXT,
                CATEGORY INTEGER NOT NULL, POSTED TEXT, UPLOADER TEXT,
                RATING REAL NOT NULL, SIMPLE_LANGUAGE TEXT,
                SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(GID))"""
        )
        // v9 SERVER_PROFILES has API_KEY column
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS SERVER_PROFILES (
                ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                NAME TEXT NOT NULL, URL TEXT NOT NULL, API_KEY TEXT,
                IS_ACTIVE INTEGER NOT NULL)"""
        )
    }

    /**
     * Creates the v11 schema (v10 tables + 4 indexes from MIGRATION_10_11).
     * v11 still has API_KEY in SERVER_PROFILES.
     */
    private fun createV11Schema(db: SupportSQLiteDatabase) {
        createV9Schema(db) // tables are identical v9-v11 except for indexes
        // Add v11 indexes
        db.execSQL("CREATE INDEX IF NOT EXISTS index_DOWNLOADS_SERVER_PROFILE_ID ON DOWNLOADS (SERVER_PROFILE_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_DOWNLOADS_TIME ON DOWNLOADS (TIME)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_HISTORY_SERVER_PROFILE_ID ON HISTORY (SERVER_PROFILE_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_HISTORY_TIME ON HISTORY (TIME)")
    }

    /**
     * Creates the v12 schema (v11 + API_KEY removed from SERVER_PROFILES).
     */
    private fun createV12Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS DOWNLOADS (
                STATE INTEGER NOT NULL, LEGACY INTEGER NOT NULL, TIME INTEGER NOT NULL,
                LABEL TEXT, ARCHIVE_URI TEXT, GID INTEGER NOT NULL, TOKEN TEXT,
                TITLE TEXT, TITLE_JPN TEXT, THUMB TEXT, CATEGORY INTEGER NOT NULL,
                POSTED TEXT, UPLOADER TEXT, RATING REAL NOT NULL, SIMPLE_LANGUAGE TEXT,
                SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS DOWNLOAD_LABELS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT, LABEL TEXT, TIME INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS DOWNLOAD_DIRNAME (
                GID INTEGER NOT NULL, DIRNAME TEXT, PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS HISTORY (
                MODE INTEGER NOT NULL, TIME INTEGER NOT NULL, GID INTEGER NOT NULL,
                TOKEN TEXT, TITLE TEXT, TITLE_JPN TEXT, THUMB TEXT,
                CATEGORY INTEGER NOT NULL, POSTED TEXT, UPLOADER TEXT,
                RATING REAL NOT NULL, SIMPLE_LANGUAGE TEXT,
                SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS LOCAL_FAVORITES (
                TIME INTEGER NOT NULL, GID INTEGER NOT NULL, TOKEN TEXT,
                TITLE TEXT, TITLE_JPN TEXT, THUMB TEXT, CATEGORY INTEGER NOT NULL,
                POSTED TEXT, UPLOADER TEXT, RATING REAL NOT NULL,
                SIMPLE_LANGUAGE TEXT, SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS QUICK_SEARCH (
                _id INTEGER PRIMARY KEY AUTOINCREMENT, NAME TEXT,
                MODE INTEGER NOT NULL, CATEGORY INTEGER NOT NULL, KEYWORD TEXT,
                ADVANCE_SEARCH INTEGER NOT NULL, MIN_RATING INTEGER NOT NULL,
                PAGE_FROM INTEGER NOT NULL, PAGE_TO INTEGER NOT NULL,
                TIME INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS FILTER (
                _id INTEGER PRIMARY KEY AUTOINCREMENT, MODE INTEGER NOT NULL,
                TEXT TEXT, ENABLE INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS Black_List (
                _id INTEGER PRIMARY KEY AUTOINCREMENT, BADGAYNAME TEXT,
                REASON TEXT, ANGRYWITH TEXT, ADD_TIME TEXT, MODE INTEGER)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_Black_List_BADGAYNAME ON Black_List (BADGAYNAME)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS Gallery_Tags (
                GID INTEGER NOT NULL, ROWS TEXT, ARTIST TEXT, COSPLAYER TEXT,
                CHARACTER TEXT, FEMALE TEXT, `GROUP` TEXT, LANGUAGE TEXT,
                MALE TEXT, MISC TEXT, MIXED TEXT, OTHER TEXT, PARODY TEXT,
                RECLASS TEXT, CREATE_TIME INTEGER, UPDATE_TIME INTEGER,
                PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS BOOKMARKS (
                PAGE INTEGER NOT NULL, TIME INTEGER NOT NULL, GID INTEGER NOT NULL,
                TOKEN TEXT, TITLE TEXT, TITLE_JPN TEXT, THUMB TEXT,
                CATEGORY INTEGER NOT NULL, POSTED TEXT, UPLOADER TEXT,
                RATING REAL NOT NULL, SIMPLE_LANGUAGE TEXT,
                SERVER_PROFILE_ID INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(GID))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS SERVER_PROFILES (
                ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                NAME TEXT NOT NULL, URL TEXT NOT NULL,
                IS_ACTIVE INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_DOWNLOADS_SERVER_PROFILE_ID ON DOWNLOADS (SERVER_PROFILE_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_DOWNLOADS_TIME ON DOWNLOADS (TIME)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_HISTORY_SERVER_PROFILE_ID ON HISTORY (SERVER_PROFILE_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_HISTORY_TIME ON HISTORY (TIME)")
    }

    /**
     * Creates the v13 schema (v12 + LABEL index on DOWNLOADS).
     */
    private fun createV13Schema(db: SupportSQLiteDatabase) {
        createV12Schema(db) // tables identical to v12
        db.execSQL("CREATE INDEX IF NOT EXISTS index_DOWNLOADS_LABEL ON DOWNLOADS (LABEL)")
    }

    // ========== SHA-256 GID computation (mirrors AppDatabase.MIGRATION_9_10) ==========

    private fun sha256Gid(arcid: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(arcid.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest, 0, 8).getLong() and Long.MAX_VALUE
    }

    // ========== Test 1: v9 -> v10 (GID recomputation via SHA-256) ==========

    @Test
    fun `migrate 9 to 10 - GIDs recomputed from TOKEN via SHA-256`() {
        db = createDatabase(9) { createV9Schema(it) }

        val token1 = "abc123def456"
        val token2 = "xyz789ghi012"
        val oldGid1 = token1.hashCode().toLong() and 0x7FFFFFFF // old-style 32-bit GID
        val oldGid2 = token2.hashCode().toLong() and 0x7FFFFFFF
        val expectedGid1 = sha256Gid(token1)
        val expectedGid2 = sha256Gid(token2)

        // Ensure old and new GIDs actually differ (validates test setup)
        assertNotEquals("GIDs should differ after SHA-256 recompute", oldGid1, expectedGid1)

        val now = System.currentTimeMillis()

        // Insert into DOWNLOADS
        db.execSQL(
            "INSERT INTO DOWNLOADS (GID, TOKEN, STATE, LEGACY, TIME, CATEGORY, RATING) VALUES (?, ?, 0, 0, ?, 0, 0.0)",
            arrayOf<Any>(oldGid1, token1, now)
        )

        // Insert into HISTORY
        db.execSQL(
            "INSERT INTO HISTORY (GID, TOKEN, MODE, TIME, CATEGORY, RATING) VALUES (?, ?, 0, ?, 0, 0.0)",
            arrayOf<Any>(oldGid2, token2, now)
        )

        // Insert DOWNLOAD_DIRNAME referencing the download's old GID
        db.execSQL(
            "INSERT INTO DOWNLOAD_DIRNAME (GID, DIRNAME) VALUES (?, ?)",
            arrayOf<Any>(oldGid1, "/storage/gallery_dir")
        )

        // Insert LOCAL_FAVORITES
        db.execSQL(
            "INSERT INTO LOCAL_FAVORITES (GID, TOKEN, TIME, CATEGORY, RATING) VALUES (?, ?, ?, 0, 0.0)",
            arrayOf<Any>(oldGid1, token1, now)
        )

        // Insert BOOKMARKS
        db.execSQL(
            "INSERT INTO BOOKMARKS (GID, TOKEN, PAGE, TIME, CATEGORY, RATING) VALUES (?, ?, 10, ?, 0, 0.0)",
            arrayOf<Any>(oldGid2, token2, now)
        )

        // Insert Gallery_Tags referencing a GID that appears in downloads
        db.execSQL(
            "INSERT INTO Gallery_Tags (GID, ARTIST) VALUES (?, ?)",
            arrayOf<Any>(oldGid1, "test_artist")
        )

        // Run migration
        AppDatabase.MIGRATION_9_10.migrate(db)

        // Verify DOWNLOADS GID was recomputed
        db.query("SELECT GID, TOKEN FROM DOWNLOADS").use { c ->
            assertTrue("DOWNLOADS should have 1 row", c.moveToFirst())
            assertEquals(expectedGid1, c.getLong(0))
            assertEquals(token1, c.getString(1))
        }

        // Verify HISTORY GID was recomputed
        db.query("SELECT GID, TOKEN FROM HISTORY").use { c ->
            assertTrue("HISTORY should have 1 row", c.moveToFirst())
            assertEquals(expectedGid2, c.getLong(0))
            assertEquals(token2, c.getString(1))
        }

        // Verify DOWNLOAD_DIRNAME GID was updated to match new download GID
        db.query("SELECT GID, DIRNAME FROM DOWNLOAD_DIRNAME").use { c ->
            assertTrue("DOWNLOAD_DIRNAME should have 1 row", c.moveToFirst())
            assertEquals(expectedGid1, c.getLong(0))
            assertEquals("/storage/gallery_dir", c.getString(1))
        }

        // Verify LOCAL_FAVORITES GID was recomputed
        db.query("SELECT GID, TOKEN FROM LOCAL_FAVORITES").use { c ->
            assertTrue("LOCAL_FAVORITES should have 1 row", c.moveToFirst())
            assertEquals(expectedGid1, c.getLong(0))
        }

        // Verify BOOKMARKS GID was recomputed
        db.query("SELECT GID, TOKEN, PAGE FROM BOOKMARKS").use { c ->
            assertTrue("BOOKMARKS should have 1 row", c.moveToFirst())
            assertEquals(expectedGid2, c.getLong(0))
            assertEquals(token2, c.getString(1))
            assertEquals(10, c.getInt(2))
        }

        // Verify Gallery_Tags GID was updated
        db.query("SELECT GID, ARTIST FROM Gallery_Tags").use { c ->
            assertTrue("Gallery_Tags should have 1 row", c.moveToFirst())
            assertEquals(expectedGid1, c.getLong(0))
            assertEquals("test_artist", c.getString(1))
        }
    }

    @Test
    fun `migrate 9 to 10 - rows with null or empty TOKEN are not changed`() {
        db = createDatabase(9) { createV9Schema(it) }

        val now = System.currentTimeMillis()

        // Insert row with null TOKEN
        db.execSQL(
            "INSERT INTO DOWNLOADS (GID, TOKEN, STATE, LEGACY, TIME, CATEGORY, RATING) VALUES (999, NULL, 0, 0, ?, 0, 0.0)",
            arrayOf(now)
        )
        // Insert row with empty TOKEN
        db.execSQL(
            "INSERT INTO HISTORY (GID, TOKEN, MODE, TIME, CATEGORY, RATING) VALUES (888, '', 0, ?, 0, 0.0)",
            arrayOf(now)
        )

        AppDatabase.MIGRATION_9_10.migrate(db)

        // Null-TOKEN row should keep original GID
        db.query("SELECT GID FROM DOWNLOADS WHERE GID = 999").use { c ->
            assertTrue("NULL TOKEN row should keep GID=999", c.moveToFirst())
        }

        // Empty-TOKEN row should keep original GID
        db.query("SELECT GID FROM HISTORY WHERE GID = 888").use { c ->
            assertTrue("Empty TOKEN row should keep GID=888", c.moveToFirst())
        }
    }

    @Test
    fun `migrate 9 to 10 - row where old GID already matches SHA-256 is not changed`() {
        db = createDatabase(9) { createV9Schema(it) }

        val token = "already_correct"
        val correctGid = sha256Gid(token)
        val now = System.currentTimeMillis()

        db.execSQL(
            "INSERT INTO DOWNLOADS (GID, TOKEN, STATE, LEGACY, TIME, CATEGORY, RATING) VALUES (?, ?, 0, 0, ?, 0, 0.0)",
            arrayOf<Any>(correctGid, token, now)
        )

        AppDatabase.MIGRATION_9_10.migrate(db)

        db.query("SELECT GID FROM DOWNLOADS").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(correctGid, c.getLong(0))
        }
    }

    // ========== Test 2: v10 -> v11 (Add indexes) ==========

    @Test
    fun `migrate 10 to 11 - four indexes created on DOWNLOADS and HISTORY`() {
        // v10 schema is identical to v9 (data migration only, no structural changes)
        db = createDatabase(10) { createV9Schema(it) }

        // Verify indexes do NOT exist yet
        val indexesBefore = getIndexNames(db)
        assertFalse("index_DOWNLOADS_SERVER_PROFILE_ID should not exist before migration",
            indexesBefore.contains("index_DOWNLOADS_SERVER_PROFILE_ID"))
        assertFalse("index_DOWNLOADS_TIME should not exist before migration",
            indexesBefore.contains("index_DOWNLOADS_TIME"))
        assertFalse("index_HISTORY_SERVER_PROFILE_ID should not exist before migration",
            indexesBefore.contains("index_HISTORY_SERVER_PROFILE_ID"))
        assertFalse("index_HISTORY_TIME should not exist before migration",
            indexesBefore.contains("index_HISTORY_TIME"))

        // Run migration
        AppDatabase.MIGRATION_10_11.migrate(db)

        // Verify all 4 indexes now exist
        val indexesAfter = getIndexNames(db)
        assertTrue("index_DOWNLOADS_SERVER_PROFILE_ID should exist after migration",
            indexesAfter.contains("index_DOWNLOADS_SERVER_PROFILE_ID"))
        assertTrue("index_DOWNLOADS_TIME should exist after migration",
            indexesAfter.contains("index_DOWNLOADS_TIME"))
        assertTrue("index_HISTORY_SERVER_PROFILE_ID should exist after migration",
            indexesAfter.contains("index_HISTORY_SERVER_PROFILE_ID"))
        assertTrue("index_HISTORY_TIME should exist after migration",
            indexesAfter.contains("index_HISTORY_TIME"))
    }

    @Test
    fun `migrate 10 to 11 - existing data preserved after index creation`() {
        db = createDatabase(10) { createV9Schema(it) }

        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO DOWNLOADS (GID, TOKEN, STATE, LEGACY, TIME, CATEGORY, RATING, SERVER_PROFILE_ID) " +
                "VALUES (1001, 'tok1', 0, 0, ?, 0, 4.5, 2)",
            arrayOf(now)
        )
        db.execSQL(
            "INSERT INTO HISTORY (GID, TOKEN, MODE, TIME, CATEGORY, RATING, SERVER_PROFILE_ID) " +
                "VALUES (2001, 'tok2', 1, ?, 3, 3.0, 1)",
            arrayOf(now)
        )

        AppDatabase.MIGRATION_10_11.migrate(db)

        // Verify data still intact
        db.query("SELECT GID, TOKEN, RATING, SERVER_PROFILE_ID FROM DOWNLOADS").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1001L, c.getLong(0))
            assertEquals("tok1", c.getString(1))
            assertEquals(4.5, c.getDouble(2), 0.001)
            assertEquals(2, c.getInt(3))
        }

        db.query("SELECT GID, TOKEN, MODE, SERVER_PROFILE_ID FROM HISTORY").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2001L, c.getLong(0))
            assertEquals("tok2", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals(1, c.getInt(3))
        }
    }

    // ========== Test 3: v11 -> v12 (Remove API_KEY from SERVER_PROFILES) ==========

    @Test
    fun `migrate 11 to 12 - API_KEY column removed from SERVER_PROFILES`() {
        db = createDatabase(11) { createV11Schema(it) }

        // Insert a server profile with API_KEY
        db.execSQL(
            "INSERT INTO SERVER_PROFILES (NAME, URL, API_KEY, IS_ACTIVE) VALUES ('My Server', 'http://lrr.local:3000', 'secret_key_123', 1)"
        )
        db.execSQL(
            "INSERT INTO SERVER_PROFILES (NAME, URL, API_KEY, IS_ACTIVE) VALUES ('Backup', 'http://backup.local:3000', NULL, 0)"
        )

        // Verify API_KEY column exists before migration
        val columnsBefore = getColumnNames(db, "SERVER_PROFILES")
        assertTrue("API_KEY should exist before migration", columnsBefore.contains("API_KEY"))

        // Run migration
        AppDatabase.MIGRATION_11_12.migrate(db)

        // Verify API_KEY column is gone
        val columnsAfter = getColumnNames(db, "SERVER_PROFILES")
        assertFalse("API_KEY should not exist after migration", columnsAfter.contains("API_KEY"))
        assertTrue("ID should still exist", columnsAfter.contains("ID"))
        assertTrue("NAME should still exist", columnsAfter.contains("NAME"))
        assertTrue("URL should still exist", columnsAfter.contains("URL"))
        assertTrue("IS_ACTIVE should still exist", columnsAfter.contains("IS_ACTIVE"))
    }

    @Test
    fun `migrate 11 to 12 - existing profile data preserved`() {
        db = createDatabase(11) { createV11Schema(it) }

        db.execSQL(
            "INSERT INTO SERVER_PROFILES (NAME, URL, API_KEY, IS_ACTIVE) VALUES ('Primary', 'http://lrr.local:3000', 'key123', 1)"
        )
        db.execSQL(
            "INSERT INTO SERVER_PROFILES (NAME, URL, API_KEY, IS_ACTIVE) VALUES ('Secondary', 'http://backup.local:3000', 'key456', 0)"
        )

        AppDatabase.MIGRATION_11_12.migrate(db)

        db.query("SELECT ID, NAME, URL, IS_ACTIVE FROM SERVER_PROFILES ORDER BY ID").use { c ->
            // First row
            assertTrue(c.moveToFirst())
            assertEquals("Primary", c.getString(1))
            assertEquals("http://lrr.local:3000", c.getString(2))
            assertEquals(1, c.getInt(3))

            // Second row
            assertTrue(c.moveToNext())
            assertEquals("Secondary", c.getString(1))
            assertEquals("http://backup.local:3000", c.getString(2))
            assertEquals(0, c.getInt(3))

            assertFalse("Should only have 2 rows", c.moveToNext())
        }
    }

    @Test
    fun `migrate 11 to 12 - SERVER_PROFILES autoincrement ID preserved`() {
        db = createDatabase(11) { createV11Schema(it) }

        db.execSQL(
            "INSERT INTO SERVER_PROFILES (NAME, URL, API_KEY, IS_ACTIVE) VALUES ('S1', 'http://a.com', 'k1', 1)"
        )

        AppDatabase.MIGRATION_11_12.migrate(db)

        // Verify ID was preserved (should be 1 from autoincrement)
        db.query("SELECT ID FROM SERVER_PROFILES").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }

        // Verify new inserts still autoincrement
        db.execSQL("INSERT INTO SERVER_PROFILES (NAME, URL, IS_ACTIVE) VALUES ('S2', 'http://b.com', 0)")
        db.query("SELECT ID FROM SERVER_PROFILES ORDER BY ID DESC LIMIT 1").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("New ID should be > 1", c.getLong(0) > 1)
        }
    }

    // ========== Test 4: v12 -> v13 (Add LABEL index on DOWNLOADS) ==========

    @Test
    fun `migrate 12 to 13 - LABEL index created on DOWNLOADS`() {
        db = createDatabase(12) { createV12Schema(it) }

        val indexesBefore = getIndexNames(db)
        assertFalse("index_DOWNLOADS_LABEL should not exist before migration",
            indexesBefore.contains("index_DOWNLOADS_LABEL"))

        AppDatabase.MIGRATION_12_13.migrate(db)

        val indexesAfter = getIndexNames(db)
        assertTrue("index_DOWNLOADS_LABEL should exist after migration",
            indexesAfter.contains("index_DOWNLOADS_LABEL"))
    }

    @Test
    fun `migrate 12 to 13 - existing data preserved after LABEL index creation`() {
        db = createDatabase(12) { createV12Schema(it) }

        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO DOWNLOADS (GID, TOKEN, STATE, LEGACY, TIME, CATEGORY, RATING, LABEL) " +
                "VALUES (5001, 'tok_label', 0, 0, ?, 0, 4.0, 'My Label')",
            arrayOf(now)
        )
        db.execSQL(
            "INSERT INTO DOWNLOADS (GID, TOKEN, STATE, LEGACY, TIME, CATEGORY, RATING, LABEL) " +
                "VALUES (5002, 'tok_null', 0, 0, ?, 0, 3.5, NULL)",
            arrayOf(now)
        )

        AppDatabase.MIGRATION_12_13.migrate(db)

        db.query("SELECT GID, TOKEN, LABEL, RATING FROM DOWNLOADS ORDER BY GID").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(5001L, c.getLong(0))
            assertEquals("tok_label", c.getString(1))
            assertEquals("My Label", c.getString(2))
            assertEquals(4.0, c.getDouble(3), 0.001)

            assertTrue(c.moveToNext())
            assertEquals(5002L, c.getLong(0))
            assertEquals("tok_null", c.getString(1))
            assertTrue(c.isNull(2))
            assertEquals(3.5, c.getDouble(3), 0.001)

            assertFalse("Should only have 2 rows", c.moveToNext())
        }
    }

    // ========== Test 4b: v13 -> v14 (Add ALLOW_CLEARTEXT to SERVER_PROFILES) ==========

    @Test
    fun `migrate 13 to 14 - ALLOW_CLEARTEXT column added with default 1`() {
        db = createDatabase(13) { createV13Schema(it) }

        // Insert profiles WITHOUT the new column (v13 schema)
        db.execSQL(
            "INSERT INTO SERVER_PROFILES (NAME, URL, IS_ACTIVE) VALUES ('Primary', 'http://lrr.local:3000', 1)"
        )
        db.execSQL(
            "INSERT INTO SERVER_PROFILES (NAME, URL, IS_ACTIVE) VALUES ('Backup', 'https://lrr.example.com', 0)"
        )

        val columnsBefore = getColumnNames(db, "SERVER_PROFILES")
        assertFalse("ALLOW_CLEARTEXT should not exist before migration",
            columnsBefore.contains("ALLOW_CLEARTEXT"))

        AppDatabase.MIGRATION_13_14.migrate(db)

        val columnsAfter = getColumnNames(db, "SERVER_PROFILES")
        assertTrue("ALLOW_CLEARTEXT should exist after migration",
            columnsAfter.contains("ALLOW_CLEARTEXT"))

        // Both pre-existing rows should be grandfathered to ALLOW_CLEARTEXT=1
        db.query("SELECT NAME, URL, IS_ACTIVE, ALLOW_CLEARTEXT FROM SERVER_PROFILES ORDER BY ID").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Primary", c.getString(0))
            assertEquals("http://lrr.local:3000", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals(1, c.getInt(3))

            assertTrue(c.moveToNext())
            assertEquals("Backup", c.getString(0))
            assertEquals("https://lrr.example.com", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals(1, c.getInt(3))

            assertFalse("Should only have 2 rows", c.moveToNext())
        }
    }

    @Test
    fun `migrate 13 to 14 - empty SERVER_PROFILES table migrates without error`() {
        db = createDatabase(13) { createV13Schema(it) }

        AppDatabase.MIGRATION_13_14.migrate(db)

        val columns = getColumnNames(db, "SERVER_PROFILES")
        assertTrue(columns.contains("ALLOW_CLEARTEXT"))

        db.query("SELECT COUNT(*) FROM SERVER_PROFILES").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun `migrate 13 to 14 - new inserts can specify ALLOW_CLEARTEXT explicitly`() {
        db = createDatabase(13) { createV13Schema(it) }
        AppDatabase.MIGRATION_13_14.migrate(db)

        db.execSQL(
            "INSERT INTO SERVER_PROFILES (NAME, URL, IS_ACTIVE, ALLOW_CLEARTEXT) " +
                "VALUES ('NoCleartext', 'http://strict.local', 1, 0)"
        )
        db.execSQL(
            "INSERT INTO SERVER_PROFILES (NAME, URL, IS_ACTIVE, ALLOW_CLEARTEXT) " +
                "VALUES ('YesCleartext', 'http://lan.local', 0, 1)"
        )

        db.query("SELECT NAME, ALLOW_CLEARTEXT FROM SERVER_PROFILES ORDER BY NAME").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("NoCleartext", c.getString(0))
            assertEquals(0, c.getInt(1))

            assertTrue(c.moveToNext())
            assertEquals("YesCleartext", c.getString(0))
            assertEquals(1, c.getInt(1))
        }
    }

    // ========== Test 5: Full migration v9 -> v14 ==========

    @Test
    fun `migrate all v9 to v14 - full chain preserves data`() {
        db = createDatabase(9) { createV9Schema(it) }

        val token = "full_chain_test_arcid"
        val oldGid = token.hashCode().toLong() and 0x7FFFFFFF
        val expectedGid = sha256Gid(token)
        val now = System.currentTimeMillis()

        // Seed v9 data across multiple tables
        db.execSQL(
            "INSERT INTO DOWNLOADS (GID, TOKEN, STATE, LEGACY, TIME, CATEGORY, RATING, TITLE) " +
                "VALUES (?, ?, 0, 0, ?, 0, 5.0, 'Test Manga')",
            arrayOf<Any>(oldGid, token, now)
        )
        db.execSQL(
            "INSERT INTO DOWNLOAD_DIRNAME (GID, DIRNAME) VALUES (?, '/test/dir')",
            arrayOf<Any>(oldGid)
        )
        db.execSQL(
            "INSERT INTO HISTORY (GID, TOKEN, MODE, TIME, CATEGORY, RATING) VALUES (?, ?, 0, ?, 0, 3.5)",
            arrayOf<Any>(oldGid, token, now)
        )
        db.execSQL(
            "INSERT INTO SERVER_PROFILES (NAME, URL, API_KEY, IS_ACTIVE) VALUES ('TestSrv', 'http://test.local', 'secret', 1)"
        )
        db.execSQL(
            "INSERT INTO Gallery_Tags (GID, ARTIST, LANGUAGE) VALUES (?, 'mangaka', 'japanese')",
            arrayOf<Any>(oldGid)
        )

        // Run all 5 migrations in order
        AppDatabase.MIGRATION_9_10.migrate(db)
        AppDatabase.MIGRATION_10_11.migrate(db)
        AppDatabase.MIGRATION_11_12.migrate(db)
        AppDatabase.MIGRATION_12_13.migrate(db)
        AppDatabase.MIGRATION_13_14.migrate(db)

        // Verify GID recomputation (v9->v10)
        db.query("SELECT GID, TOKEN, TITLE, RATING FROM DOWNLOADS").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(expectedGid, c.getLong(0))
            assertEquals(token, c.getString(1))
            assertEquals("Test Manga", c.getString(2))
            assertEquals(5.0, c.getDouble(3), 0.001)
        }

        db.query("SELECT GID, DIRNAME FROM DOWNLOAD_DIRNAME").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(expectedGid, c.getLong(0))
            assertEquals("/test/dir", c.getString(1))
        }

        db.query("SELECT GID, TOKEN, RATING FROM HISTORY").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(expectedGid, c.getLong(0))
            assertEquals(token, c.getString(1))
            assertEquals(3.5, c.getDouble(2), 0.001)
        }

        db.query("SELECT GID, ARTIST, LANGUAGE FROM Gallery_Tags").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(expectedGid, c.getLong(0))
            assertEquals("mangaka", c.getString(1))
            assertEquals("japanese", c.getString(2))
        }

        // Verify indexes exist (v10->v11)
        val indexes = getIndexNames(db)
        assertTrue(indexes.contains("index_DOWNLOADS_SERVER_PROFILE_ID"))
        assertTrue(indexes.contains("index_DOWNLOADS_TIME"))
        assertTrue(indexes.contains("index_HISTORY_SERVER_PROFILE_ID"))
        assertTrue(indexes.contains("index_HISTORY_TIME"))

        // Verify LABEL index (v12->v13)
        assertTrue(indexes.contains("index_DOWNLOADS_LABEL"))

        // Verify API_KEY removed from SERVER_PROFILES (v11->v12)
        val columns = getColumnNames(db, "SERVER_PROFILES")
        assertFalse("API_KEY should be removed", columns.contains("API_KEY"))

        db.query("SELECT NAME, URL, IS_ACTIVE, ALLOW_CLEARTEXT FROM SERVER_PROFILES").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("TestSrv", c.getString(0))
            assertEquals("http://test.local", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals(1, c.getInt(3))
        }
    }

    @Test
    fun `migrate all v9 to v14 - empty database succeeds`() {
        db = createDatabase(9) { createV9Schema(it) }

        // Run all migrations on empty database -- should not throw
        AppDatabase.MIGRATION_9_10.migrate(db)
        AppDatabase.MIGRATION_10_11.migrate(db)
        AppDatabase.MIGRATION_11_12.migrate(db)
        AppDatabase.MIGRATION_12_13.migrate(db)
        AppDatabase.MIGRATION_13_14.migrate(db)

        // Verify tables still exist and are queryable
        db.query("SELECT COUNT(*) FROM DOWNLOADS").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM SERVER_PROFILES").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun `migrate all v9 to v14 - final schema matches Room v14`() {
        db = createDatabase(9) { createV9Schema(it) }

        AppDatabase.MIGRATION_9_10.migrate(db)
        AppDatabase.MIGRATION_10_11.migrate(db)
        AppDatabase.MIGRATION_11_12.migrate(db)
        AppDatabase.MIGRATION_12_13.migrate(db)
        AppDatabase.MIGRATION_13_14.migrate(db)

        // Verify all 11 tables exist
        val tables = getTableNames(db)
        val expectedTables = setOf(
            "DOWNLOADS", "DOWNLOAD_LABELS", "DOWNLOAD_DIRNAME",
            "HISTORY", "LOCAL_FAVORITES", "QUICK_SEARCH",
            "FILTER", "Black_List", "Gallery_Tags",
            "BOOKMARKS", "SERVER_PROFILES"
        )
        assertEquals(expectedTables, tables)

        // Verify SERVER_PROFILES v14 columns: ID, NAME, URL, IS_ACTIVE, ALLOW_CLEARTEXT
        val profileCols = getColumnNames(db, "SERVER_PROFILES")
        assertEquals(setOf("ID", "NAME", "URL", "IS_ACTIVE", "ALLOW_CLEARTEXT"), profileCols)

        // Verify v11 indexes on DOWNLOADS and HISTORY
        val indexes = getIndexNames(db)
        assertTrue(indexes.contains("index_DOWNLOADS_SERVER_PROFILE_ID"))
        assertTrue(indexes.contains("index_DOWNLOADS_TIME"))
        assertTrue(indexes.contains("index_HISTORY_SERVER_PROFILE_ID"))
        assertTrue(indexes.contains("index_HISTORY_TIME"))
        assertTrue(indexes.contains("index_Black_List_BADGAYNAME"))
        // Verify v13 LABEL index
        assertTrue(indexes.contains("index_DOWNLOADS_LABEL"))
    }

    // ========== Utility functions ==========

    private fun getIndexNames(db: SupportSQLiteDatabase): Set<String> {
        val indexes = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
        ).use { c ->
            while (c.moveToNext()) {
                indexes.add(c.getString(0))
            }
        }
        return indexes
    }

    private fun getColumnNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info($table)").use { c ->
            while (c.moveToNext()) {
                columns.add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }
        return columns
    }

    private fun getTableNames(db: SupportSQLiteDatabase): Set<String> {
        val tables = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' " +
                "AND name != 'android_metadata'"
        ).use { c ->
            while (c.moveToNext()) {
                tables.add(c.getString(0))
            }
        }
        return tables
    }
}
