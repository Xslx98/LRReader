package com.hippo.ehviewer.dao

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room database schema integrity and DAO CRUD tests.
 *
 * Tests the residual entity/DAO surface that survives L1: the
 * unified `ARCHIVE_LOCAL_STATE` table is covered by
 * [ArchiveLocalStateDaoTest] separately, and migrations are covered
 * by per-version tests under `RoomMigrationVxxVxxTest`.
 *
 * Run with: ./gradlew testAppReleaseDebugUnitTest --tests "*.RoomMigrationTest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class) // Bypass EhApplication native lib
class RoomMigrationTest {

    private lateinit var db: AppDatabase
    private lateinit var sqliteDb: SupportSQLiteDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        sqliteDb = db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ========== Schema Integrity Tests ==========

    @Test
    fun `schema has all expected tables`() {
        val cursor = sqliteDb.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' " +
                "AND name != 'android_metadata'"
        )
        val tables = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            tables.add(cursor.getString(0))
        }
        cursor.close()

        // Post-L1-4: the legacy three tables (DOWNLOADS, HISTORY,
        // LOCAL_FAVORITES) are gone — their subsystems live on
        // ARCHIVE_LOCAL_STATE. DOWNLOAD_LABELS / DOWNLOAD_DIRNAME /
        // QUICK_SEARCH / SERVER_PROFILES carry distinct concerns and
        // remain on their own tables. v28 added SEARCH_HISTORY (issue #12),
        // v29 added DAILY_READING_AGGREGATE (issue #20).
        val expectedTables = setOf(
            "DOWNLOAD_LABELS", "DOWNLOAD_DIRNAME",
            "QUICK_SEARCH", "SERVER_PROFILES",
            "ARCHIVE_LOCAL_STATE",
            "SEARCH_HISTORY", "DAILY_READING_AGGREGATE"
        )
        assertEquals(expectedTables, tables)
    }

    @Test
    fun `schema has v19 indexes on QUICK_SEARCH and DOWNLOAD_LABELS`() {
        val cursor = sqliteDb.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
        )
        val indexes = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            indexes.add(cursor.getString(0))
        }
        cursor.close()

        assertTrue(
            "index_QUICK_SEARCH_TIME should exist",
            indexes.contains("index_QUICK_SEARCH_TIME")
        )
        assertTrue(
            "index_DOWNLOAD_LABELS_TIME should exist",
            indexes.contains("index_DOWNLOAD_LABELS_TIME")
        )
    }

    // ========== DownloadRoomDao CRUD Tests (residual tables) ==========

    @Test
    fun `DownloadDao label CRUD`() = runBlocking {
        val dao = db.downloadDao()
        val label = DownloadLabel().apply {
            label = "Test Label"
            time = System.currentTimeMillis()
        }
        dao.insertLabel(label)

        val all = dao.getAllDownloadLabels()
        assertEquals(1, all.size)
        assertEquals("Test Label", all[0].label)
    }

    @Test
    fun `DownloadDao dirname CRUD`() = runBlocking {
        val dao = db.downloadDao()
        val dirname = DownloadDirname(arcid = "arcid_8001", dirname = "/storage/gallery_8001")
        dao.insertDirname(dirname)

        val result = dao.loadDirname("arcid_8001")
        assertNotNull(result)
        assertEquals("/storage/gallery_8001", result!!.dirname)
    }

    // ========== BrowsingRoomDao CRUD Tests (residual table) ==========

    @Test
    fun `BrowsingDao quickSearch insert and query`() = runBlocking {
        val dao = db.browsingDao()
        val qs = QuickSearch().apply {
            name = "Test Search"
            keyword = "test"
            time = System.currentTimeMillis()
        }
        dao.insertQuickSearch(qs)

        val all = dao.getAllQuickSearch()
        assertTrue(all.any { it.name == "Test Search" })
    }

    // ========== MiscRoomDao CRUD Tests ==========

    @Test
    fun `MiscDao serverProfile CRUD`() = runBlocking {
        val dao = db.miscDao()
        val profile = ServerProfile(
            name = "My Server",
            url = "http://example.com",
            isActive = true
        )
        dao.insertServerProfile(profile)

        val all = dao.getAllServerProfiles()
        assertEquals(1, all.size)
        assertEquals("My Server", all[0].name)
        assertEquals("http://example.com", all[0].url)
        assertTrue(all[0].isActive)
    }

    @Test
    fun `MiscDao serverProfile active deactivation`() = runBlocking {
        val dao = db.miscDao()
        dao.insertServerProfile(ServerProfile(name = "S1", url = "http://a.com", isActive = true))
        dao.insertServerProfile(ServerProfile(name = "S2", url = "http://b.com", isActive = true))

        dao.deactivateAllProfiles()

        val all = dao.getAllServerProfiles()
        assertTrue(all.none { it.isActive })
    }
}
