package com.hippo.ehviewer.dao

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
 * One-time lift of the retired legacy SearchDatabase file
 * (search_database.db, table `suggestions`) into the per-profile Room store.
 *
 * Contract: import the 50 most-recent legacy rows into the given profile
 * keeping their original dates, never overwrite an entry the user already
 * re-created post-migration (INSERT OR IGNORE), delete the legacy file only
 * after a completed import, and leave everything untouched when no active
 * profile exists yet (retried next boot).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LegacySearchHistoryImporterTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("search_database.db")
    }

    private fun createLegacyDb(vararg rows: Pair<String, Long>) {
        val file = context.getDatabasePath("search_database.db")
        file.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        legacy.use { l ->
            l.execSQL("CREATE TABLE IF NOT EXISTS suggestions (_id INTEGER PRIMARY KEY, query TEXT, date LONG)")
            for ((q, d) in rows) {
                l.execSQL("INSERT INTO suggestions (query, date) VALUES (?, ?)", arrayOf<Any>(q, d))
            }
        }
    }

    @Test
    fun import_liftsRowsIntoProfile_keepsDates_deletesFile() = runBlocking {
        createLegacyDb("older" to 100L, "newer" to 200L, "  " to 300L)

        LegacySearchHistoryImporter.importIfPresent(context, db, activeProfileId = 7L)

        val rows = db.browsingDao().getRecentSearchHistory(7L, 10)
        assertEquals(listOf("newer", "older"), rows.map { it.query })
        assertEquals(listOf(200L, 100L), rows.map { it.lastUsed })
        assertFalse("legacy file must be deleted", context.getDatabasePath("search_database.db").exists())
    }

    @Test
    fun import_neverOverwrites_freshPostMigrationEntry() = runBlocking {
        createLegacyDb("touhou" to 100L)
        db.browsingDao().upsertSearchHistory(SearchHistoryEntry("touhou", 7L, 9_999L))

        LegacySearchHistoryImporter.importIfPresent(context, db, activeProfileId = 7L)

        val rows = db.browsingDao().getRecentSearchHistory(7L, 10)
        assertEquals(listOf(9_999L), rows.map { it.lastUsed })
    }

    @Test
    fun import_withoutProfile_leavesFileForNextBoot() = runBlocking {
        createLegacyDb("kept" to 100L)

        LegacySearchHistoryImporter.importIfPresent(context, db, activeProfileId = 0L)

        assertTrue(context.getDatabasePath("search_database.db").exists())
        assertEquals(0, db.browsingDao().getRecentSearchHistory(0L, 10).size)
    }

    @Test
    fun import_withoutLegacyFile_isANoOp() = runBlocking {
        LegacySearchHistoryImporter.importIfPresent(context, db, activeProfileId = 7L)
        assertEquals(0, db.browsingDao().getRecentSearchHistory(7L, 10).size)
    }

    @Test
    fun import_capsAtFiftyMostRecent() = runBlocking {
        createLegacyDb(*(1..60).map { "q$it" to it.toLong() }.toTypedArray())

        LegacySearchHistoryImporter.importIfPresent(context, db, activeProfileId = 7L)

        val rows = db.browsingDao().getRecentSearchHistory(7L, 100)
        assertEquals(50, rows.size)
        assertEquals("q60", rows.first().query)
        assertTrue(rows.none { it.query == "q10" })
    }
}
