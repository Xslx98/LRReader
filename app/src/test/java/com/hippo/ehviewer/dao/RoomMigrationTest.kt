package com.hippo.ehviewer.dao

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
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
 * These tests ensure:
 * 1. The Room-managed schema creates all 11 tables correctly
 * 2. Column defaults (e.g., SERVER_PROFILE_ID) work as expected
 * 3. Basic CRUD operations work for all 3 DAOs
 * 4. Data roundtrip through Room Entity ↔ SQLite is correct
 *
 * When future migrations are added (v9 → v10, etc.), add migration-specific
 * tests using MigrationTestHelper with androidTest instrumentation.
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
    fun `schema has all 11 expected tables`() {
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

        val expectedTables = setOf(
            "DOWNLOADS", "DOWNLOAD_LABELS", "DOWNLOAD_DIRNAME",
            "HISTORY", "LOCAL_FAVORITES", "QUICK_SEARCH",
            "FILTER", "Black_List", "Gallery_Tags",
            "BOOKMARKS", "SERVER_PROFILES"
        )
        assertEquals(expectedTables, tables)
    }

    @Test
    fun `DOWNLOADS table has expected columns`() {
        val cursor = sqliteDb.query("PRAGMA table_info(DOWNLOADS)")
        val columns = mutableMapOf<String, String>() // name -> type
        while (cursor.moveToNext()) {
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
            columns[name] = type
        }
        cursor.close()

        assertTrue("GID column missing", "GID" in columns)
        assertTrue("STATE column missing", "STATE" in columns)
        assertTrue("SERVER_PROFILE_ID column missing", "SERVER_PROFILE_ID" in columns)
        assertTrue("TITLE column missing", "TITLE" in columns)
        assertTrue("ARCHIVE_URI column missing", "ARCHIVE_URI" in columns)
    }

    @Test
    fun `SERVER_PROFILE_ID default value is 0 across tables`() {
        // Verify DOWNLOADS
        sqliteDb.execSQL(
            "INSERT INTO DOWNLOADS (GID, STATE, LEGACY, TIME, CATEGORY, RATING) " +
                "VALUES (101, 0, 0, ${System.currentTimeMillis()}, 0, 0.0)"
        )
        val cur1 = sqliteDb.query("SELECT SERVER_PROFILE_ID FROM DOWNLOADS WHERE GID = 101")
        assertTrue(cur1.moveToFirst())
        assertEquals(0, cur1.getInt(0))
        cur1.close()

        // Verify HISTORY
        sqliteDb.execSQL(
            "INSERT INTO HISTORY (GID, MODE, TIME, CATEGORY, RATING) " +
                "VALUES (102, 0, ${System.currentTimeMillis()}, 0, 0.0)"
        )
        val cur2 = sqliteDb.query("SELECT SERVER_PROFILE_ID FROM HISTORY WHERE GID = 102")
        assertTrue(cur2.moveToFirst())
        assertEquals(0, cur2.getInt(0))
        cur2.close()
    }

    @Test
    fun `Black_List table has BADGAYNAME index`() {
        val cursor = sqliteDb.query("PRAGMA index_list('Black_List')")
        val indices = mutableListOf<String>()
        while (cursor.moveToNext()) {
            indices.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        cursor.close()

        assertTrue(
            "Expected index on BADGAYNAME",
            indices.any { it.contains("BADGAYNAME") }
        )
    }

    // ========== DownloadRoomDao CRUD Tests ==========

    @Test
    fun `DownloadDao insert and query`() = runBlocking {
        val dao = db.downloadDao()
        val info = DownloadInfo().apply {
            gid = 1001L
            token = "test_token"
            title = "Test Gallery"
            state = DownloadInfo.STATE_NONE
            time = System.currentTimeMillis()
        }
        dao.insert(info)

        val result = dao.loadDownload(1001L)
        assertNotNull(result)
        assertEquals("test_token", result!!.token)
        assertEquals("Test Gallery", result.title)
    }

    @Test
    fun `DownloadDao update`() = runBlocking {
        val dao = db.downloadDao()
        val info = DownloadInfo().apply {
            gid = 2001L
            state = DownloadInfo.STATE_NONE
            time = System.currentTimeMillis()
        }
        dao.insert(info)

        info.state = DownloadInfo.STATE_DOWNLOAD
        dao.update(info)
        val result = dao.loadDownload(2001L)
        assertEquals(DownloadInfo.STATE_DOWNLOAD, result!!.state)
    }

    @Test
    fun `DownloadDao delete`() = runBlocking {
        val dao = db.downloadDao()
        val info = DownloadInfo().apply {
            gid = 3001L
            state = DownloadInfo.STATE_NONE
            time = System.currentTimeMillis()
        }
        dao.insert(info)
        dao.deleteDownloadByKey(3001L)

        assertNull(dao.loadDownload(3001L))
    }

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
        val dirname = DownloadDirname().apply {
            gid = 8001L
            dirname = "/storage/gallery_8001"
        }
        dao.insertDirname(dirname)

        val result = dao.loadDirname(8001L)
        assertNotNull(result)
        assertEquals("/storage/gallery_8001", result!!.dirname)
    }

    // ========== BrowsingRoomDao CRUD Tests ==========

    @Test
    fun `BrowsingDao history insert and query`() = runBlocking {
        val dao = db.browsingDao()
        val history = HistoryInfo().apply {
            gid = 4001L
            token = "hist_token"
            title = "History Gallery"
            time = System.currentTimeMillis()
            mode = 0
        }
        dao.insertHistory(history)

        val all = dao.getAllHistory()
        assertTrue(all.any { it.gid == 4001L })
    }

    @Test
    fun `BrowsingDao history count and trim`() = runBlocking {
        val dao = db.browsingDao()
        val now = System.currentTimeMillis()
        for (i in 1..5) {
            dao.insertHistory(HistoryInfo().apply {
                gid = (9000 + i).toLong()
                time = now + i
                mode = 0
            })
        }

        assertEquals(5, dao.countHistory())

        dao.trimHistoryTo(3)
        assertEquals(3, dao.countHistory())
    }

    @Test
    fun `BrowsingDao local favorite insert and query`() = runBlocking {
        val dao = db.browsingDao()
        val fav = LocalFavoriteInfo().apply {
            gid = 5001L
            token = "fav_token"
            title = "Favorite Gallery"
            time = System.currentTimeMillis()
        }
        dao.insertLocalFavorite(fav)

        val all = dao.getAllLocalFavorites()
        assertTrue(all.any { it.gid == 5001L })
    }

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

    @Test
    fun `BrowsingDao filter insert and query`() = runBlocking {
        val dao = db.browsingDao()
        val filter = Filter().apply {
            mode = 0 // title filter mode
            text = "test_filter"
            enable = true
        }
        dao.insertFilter(filter)

        val all = dao.getAllFilters()
        assertTrue(all.any { it.text == "test_filter" })
    }

    // ========== MiscRoomDao CRUD Tests ==========

    @Test
    fun `MiscDao bookmark insert and query`() = runBlocking {
        val dao = db.miscDao()
        val bookmark = BookmarkInfo().apply {
            gid = 6001L
            token = "bm_token"
            title = "Bookmarked Gallery"
            page = 42
            time = System.currentTimeMillis()
        }
        dao.insertBookmark(bookmark)

        val result = dao.loadBookmark(6001L)
        assertNotNull(result)
        assertEquals(42, result!!.page)
    }

    @Test
    fun `MiscDao serverProfile CRUD`() = runBlocking {
        val dao = db.miscDao()
        val profile = ServerProfile(
            name = "My Server",
            url = "http://example.com",
            apiKey = "test_key",
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

    @Test
    fun `MiscDao galleryTags insert and query`() = runBlocking {
        val dao = db.miscDao()
        val tags = GalleryTags().apply {
            gid = 7001L
            artist = "test_artist"
            language = "chinese"
        }
        dao.insertGalleryTags(tags)

        val result = dao.queryGalleryTags(7001L)
        assertNotNull(result)
        assertEquals("test_artist", result!!.artist)
        assertEquals("chinese", result.language)
    }

    @Test
    fun `MiscDao blackList insert and query`() = runBlocking {
        val dao = db.miscDao()
        val bl = BlackList().apply {
            badgayname = "test_user"
            reason = "spam"
            mode = 0
        }
        dao.insertBlackList(bl)

        val all = dao.getAllBlackList()
        assertTrue(all.any { it.badgayname == "test_user" })
    }

    // ========== Migration Testing Guide ==========

    // Currently at schema v9 (baseline). When a migration is needed:
    //
    // 1. Add the migration to AppDatabase (e.g., MIGRATION_9_10)
    // 2. Create an androidTest with MigrationTestHelper:
    //
    //    @get:Rule
    //    val helper = MigrationTestHelper(
    //        InstrumentationRegistry.getInstrumentation(),
    //        AppDatabase::class.java.canonicalName
    //    )
    //
    //    @Test
    //    fun migrate9To10() {
    //        val db = helper.createDatabase("test", 9).apply {
    //            execSQL("INSERT INTO DOWNLOADS ...")
    //            close()
    //        }
    //        val migrated = helper.runMigrationsAndValidate("test", 10, true, MIGRATION_9_10)
    //        // verify data with cursor queries
    //    }
    //
    // MigrationTestHelper requires InstrumentationRegistry and schema JSON files
    // in androidTest assets (not unit test). Use androidTest for migration-path testing.
}
