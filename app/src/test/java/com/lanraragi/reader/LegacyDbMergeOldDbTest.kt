package com.lanraragi.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ArchiveLocalState
import com.lanraragi.reader.dao.DownloadState
import com.lanraragi.reader.dao.ArchiveLocalStateJson
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.lanraragi.reader.dao.legacy.LegacyDb

/**
 * Tests for [LegacyDb.mergeOldDB] after W1-7: the legacy SQLite-to-Room merge is now
 * a `suspend fun` with no internal `runBlocking`. The caller in `LRReaderApplication`
 * already runs it on a `Dispatchers.IO`-backed [kotlinx.coroutines.CoroutineScope],
 * so the previous `runBlocking` was pinning an IO worker for nothing.
 *
 * Tests verify:
 * 1. With no legacy `data` SQLite database present, the function is a clean no-op
 *    that does not throw and leaves the Room DB untouched.
 * 2. A real legacy database merges its history rows with epoch-second lastreadtime.
 */
@Suppress("DEPRECATION") // upsert() seeds live rows directly
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LegacyDbMergeOldDbTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // In-memory Room DB injected directly into LegacyDb.sDatabase via reflection,
        // bypassing LegacyDb.initialize() (which depends on Settings).
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        val dbField = LegacyDb::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(LegacyDb, db)

        // Make sure no stale legacy DB exists from a previous test run
        context.getDatabasePath("data")?.takeIf { it.exists() }?.delete()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun mergeOldDB_callableFromCoroutine_noOpWhenNoLegacyDb() = runTest {
        // Compile-time proof: this call only compiles if mergeOldDB is `suspend`.
        // Behavioural proof: with no legacy `data` SQLite file, the SQLiteOpenHelper
        // creates an empty schema, the rawQuery loops short-circuit (no rows), the
        // helper closes, and the function returns cleanly without touching Room.
        LegacyDb.mergeOldDB(context)

        // Verify the Room DB was not mutated as a side effect: no row in the
        // unified ARCHIVE_LOCAL_STATE table (any subsystem), and none in the
        // still-distinct quick-search table.
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ARCHIVE_LOCAL_STATE").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        assertTrue(db.browsingDao().getAllQuickSearch().isEmpty())
    }

    @Test
    fun mergeOldDB_liftsLegacyHistoryTime_asEpochSecondLastreadtime() = runTest {
        // Seed a legacy `data` SQLite DB the way the pre-Room app left it:
        // `gallery` columns read by the merge are GID(0)/ARCID(1)/TITLE(2)/
        // THUMB(5)/RATING(7); `history` is GID(0)/MODE(1)/TIME(2) with TIME
        // in device milliseconds. The lifted archive_json `lastreadtime`
        // must be epoch SECONDS while HISTORY_TIME keeps the milliseconds.
        val dbFile = context.getDatabasePath("data")
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { legacy ->
            legacy.version = 6
            legacy.execSQL(
                "CREATE TABLE gallery (GID INTEGER PRIMARY KEY, ARCID TEXT, TITLE TEXT, " +
                    "POSTED TEXT, CATEGORY INTEGER, THUMB TEXT, UPLOADER TEXT, RATING REAL)"
            )
            legacy.execSQL(
                "CREATE TABLE history (GID INTEGER PRIMARY KEY, MODE INTEGER, TIME INTEGER)"
            )
            legacy.execSQL(
                "INSERT INTO gallery VALUES " +
                    "(1, 'arc-legacy', 'Legacy Title', NULL, 0, 'http://t/l.jpg', NULL, 4.0)"
            )
            legacy.execSQL("INSERT INTO history VALUES (1, 2, 1700000001234)")
        }

        LegacyDb.mergeOldDB(context)

        val rows = db.archiveLocalStateDao().getAllHistory()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(1700000001234L, row.historyTime)
        val stored = ArchiveLocalStateJson.decodeFromString(Archive.serializer(), row.archiveJson)
        assertEquals(
            "legacy history TIME is milliseconds; lifted archive_json must be epoch seconds",
            1700000001L, stored.lastreadtime
        )
        // This legacy file has no favourite/tag/download tables: they count
        // as empty, so the merge still completes.
        assertFalse(LegacyDb.shouldMerge(context))
    }

    @Test
    fun mergeOldDB_canBeCalledTwice_idempotent() = runTest {
        // Calling twice in a row should not throw, and `sHasOldDB` should remain false.
        LegacyDb.mergeOldDB(context)
        LegacyDb.mergeOldDB(context)
        assertFalse("needMerge() should be false after merge completes", LegacyDb.needMerge())
    }

    // ── Legacy data never overwrites live rows (audit 2026-11-15 B1) ──

    /**
     * Seed a legacy `data` DB with every table the merge reads.
     * [galleries] are (gid, arcid); [history] (gid, mode, ms); [favorites]
     * gids; [downloads] (gid, state code, legacy, time); [quickSearches]
     * (name, keyword).
     */
    private fun seedLegacy(
        galleries: List<Pair<Int, String>>,
        history: List<Triple<Int, Int, Long>> = emptyList(),
        favorites: List<Int> = emptyList(),
        downloads: List<List<Long>> = emptyList(),
        quickSearches: List<Pair<String, String>> = emptyList(),
    ) {
        val dbFile = context.getDatabasePath("data")
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { legacy ->
            legacy.version = 6
            legacy.execSQL(
                "CREATE TABLE gallery (GID INTEGER PRIMARY KEY, ARCID TEXT, TITLE TEXT, " +
                    "POSTED TEXT, CATEGORY INTEGER, THUMB TEXT, UPLOADER TEXT, RATING REAL)"
            )
            legacy.execSQL("CREATE TABLE history (GID INTEGER PRIMARY KEY, MODE INTEGER, TIME INTEGER)")
            legacy.execSQL("CREATE TABLE local_favourite (GID INTEGER PRIMARY KEY)")
            legacy.execSQL(
                "CREATE TABLE tag (TIME INTEGER, NAME TEXT, MODE INTEGER, CATEGORY INTEGER, " +
                    "KEYWORD TEXT, ADVANCE INTEGER, MIN_RATING INTEGER, TAG TEXT)"
            )
            legacy.execSQL(
                "CREATE TABLE download (GID INTEGER PRIMARY KEY, X TEXT, STATE INTEGER, " +
                    "LEGACY INTEGER, TIME INTEGER)"
            )
            galleries.forEach { (gid, arcid) ->
                legacy.execSQL("INSERT INTO gallery VALUES ($gid, '$arcid', 'T', NULL, 0, '', NULL, 0)")
            }
            history.forEach { (gid, mode, ms) -> legacy.execSQL("INSERT INTO history VALUES ($gid, $mode, $ms)") }
            favorites.forEach { gid -> legacy.execSQL("INSERT INTO local_favourite VALUES ($gid)") }
            quickSearches.forEachIndexed { i, (name, keyword) ->
                legacy.execSQL("INSERT INTO tag VALUES ($i, '$name', 0, 0, '$keyword', 0, 0, NULL)")
            }
            downloads.forEach { (gid, state, left, time) ->
                legacy.execSQL("INSERT INTO download VALUES ($gid, NULL, $state, $left, $time)")
            }
        }
    }

    @Test
    fun mergeOldDB_neverOverwritesLiveRoomRow() = runTest {
        val liveJson = """{"arcid":"arc-live","title":"Live","tags":{"artist":["x"]}}"""
        db.archiveLocalStateDao().upsert(
            ArchiveLocalState(
                arcid = "arc-live", serverProfileId = 0L, archiveJson = liveJson,
                historyTime = 9_000L, downloadState = DownloadState.FINISH, downloadTime = 5L,
            )
        )
        seedLegacy(
            listOf(1 to "arc-live"),
            history = listOf(Triple(1, 0, 1_000L)),
            favorites = listOf(1),
            downloads = listOf(listOf(1L, DownloadState.FAILED.code.toLong(), 3L, 77L)),
        )

        LegacyDb.mergeOldDB(context)

        val row = db.archiveLocalStateDao().loadByArcidAndProfile("arc-live", 0L)!!
        assertEquals(liveJson, row.archiveJson)
        assertEquals(9_000L, row.historyTime)
        assertEquals(DownloadState.FINISH, row.downloadState)
        assertEquals(5L, row.downloadTime)
        assertNotNull("an absent subsystem is still filled in", row.favoriteTime)
    }

    @Test
    fun mergeOldDB_mergesSeveralSubsystemsOfOneGalleryIntoOneRow() = runTest {
        seedLegacy(
            listOf(1 to "arc-both"),
            history = listOf(Triple(1, 2, 4_000L)),
            favorites = listOf(1),
            downloads = listOf(listOf(1L, DownloadState.FINISH.code.toLong(), 0L, 88L)),
        )

        LegacyDb.mergeOldDB(context)

        val row = db.archiveLocalStateDao().loadByArcidAndProfile("arc-both", 0L)!!
        assertNotNull(row.favoriteTime)
        assertEquals(4_000L, row.historyTime)
        assertEquals(2, row.historyMode)
        assertEquals(DownloadState.FINISH, row.downloadState)
        assertEquals(88L, row.downloadTime)
    }

    @Test
    fun mergeOldDB_skipsLegacyDownload_whenAnotherProfileAlreadyDownloadsTheArcid() = runTest {
        db.archiveLocalStateDao().upsert(
            ArchiveLocalState(
                arcid = "arc-dl", serverProfileId = 4L, archiveJson = """{"arcid":"arc-dl"}""",
                downloadState = DownloadState.FINISH, downloadTime = 1L,
            )
        )
        seedLegacy(
            listOf(1 to "arc-dl"),
            downloads = listOf(listOf(1L, DownloadState.FINISH.code.toLong(), 0L, 9L)),
        )

        LegacyDb.mergeOldDB(context)

        assertEquals(1, db.archiveLocalStateDao().getAllDownloads().count { it.arcid == "arc-dl" })
        assertNull(db.archiveLocalStateDao().loadByArcidAndProfile("arc-dl", 0L))
    }

    // ── Runs until it completes (audit 2026-11-15 B1) ──

    @Test
    fun mergeOldDB_deletesLegacyFile_andMarksDone_soNextBootSkips() = runTest {
        seedLegacy(listOf(1 to "arc-a"), history = listOf(Triple(1, 0, 1_000L)))
        assertTrue(LegacyDb.shouldMerge(context))

        LegacyDb.mergeOldDB(context)

        assertFalse("legacy file must be gone", context.getDatabasePath("data").exists())
        assertFalse(LegacyDb.shouldMerge(context))
    }

    @Test
    fun shouldMerge_isFalse_whenMarkerSet_evenIfLegacyFileSurvived() = runTest {
        seedLegacy(listOf(1 to "arc-a"))
        context.getSharedPreferences("boot_cleanup", Context.MODE_PRIVATE)
            .edit().putBoolean(LegacyDb.PREF_MERGED, true).commit()

        assertFalse(LegacyDb.shouldMerge(context))
    }

    @Test
    fun mergeOldDB_failedStep_keepsFileAndMarkerUnset_soItRetries() = runTest {
        // A history table without the TIME column makes that step throw.
        val dbFile = context.getDatabasePath("data")
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { legacy ->
            legacy.version = 6
            legacy.execSQL(
                "CREATE TABLE gallery (GID INTEGER PRIMARY KEY, ARCID TEXT, TITLE TEXT, " +
                    "POSTED TEXT, CATEGORY INTEGER, THUMB TEXT, UPLOADER TEXT, RATING REAL)"
            )
            legacy.execSQL("CREATE TABLE history (GID INTEGER PRIMARY KEY, MODE INTEGER)")
            legacy.execSQL("INSERT INTO gallery VALUES (1, 'arc-a', 'T', NULL, 0, '', NULL, 0)")
            legacy.execSQL("INSERT INTO history VALUES (1, 0)")
        }

        LegacyDb.mergeOldDB(context)

        assertTrue("an incomplete merge keeps its source", dbFile.exists())
        assertTrue(LegacyDb.shouldMerge(context))
    }

    @Test
    fun mergeOldDB_secondRunOverSameFile_changesNothing() = runTest {
        // A run that did not complete leaves the file; the retry must not
        // duplicate or alter what the first run wrote.
        fun seed() = seedLegacy(
            listOf(1 to "arc-a"),
            history = listOf(Triple(1, 1, 2_000L)),
            favorites = listOf(1),
            quickSearches = listOf("Saved" to "artist:x"),
        )
        seed()
        LegacyDb.mergeOldDB(context)
        val rows = db.archiveLocalStateDao().getAllHistory()
        val searches = db.browsingDao().getAllQuickSearch().map { it.name to it.keyword }

        seed()
        LegacyDb.mergeOldDB(context)

        assertEquals(rows, db.archiveLocalStateDao().getAllHistory())
        assertEquals(searches, db.browsingDao().getAllQuickSearch().map { it.name to it.keyword })
        assertEquals(1, searches.size)
    }
}
