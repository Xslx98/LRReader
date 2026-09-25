package com.lanraragi.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ArchiveLocalStateJson
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    }

    @Test
    fun mergeOldDB_canBeCalledTwice_idempotent() = runTest {
        // Calling twice in a row should not throw, and `sHasOldDB` should remain false.
        LegacyDb.mergeOldDB(context)
        LegacyDb.mergeOldDB(context)
        assertFalse("needMerge() should be false after merge completes", LegacyDb.needMerge())
    }
}
