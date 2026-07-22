package com.hippo.ehviewer.ui.scene.gallery.list

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.containedTestScope
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.SearchHistoryRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ProfileSearchHistoryStore] bridges the synchronous SearchBar suggestion
 * path to the suspend [SearchHistoryRepository]: reads come from an in-memory
 * cache, mutations apply to the cache synchronously (so the suggestion list
 * repaints correctly right after a record/delete) and persist asynchronously.
 *
 * Unconfined scope + immediate Room executors make the async legs complete
 * inline, so cache/DB agreement is directly assertable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ProfileSearchHistoryStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SearchHistoryRepository
    private val scope = containedTestScope()
    private var profileId = 1L
    private var now = 1_000L

    private lateinit var store: ProfileSearchHistoryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repo = SearchHistoryRepository(db.browsingDao(), db, now = { ++now })
        store = ProfileSearchHistoryStore(scope, repo) { profileId }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun refresh_populatesCacheFromRepository() = runBlocking {
        repo.recordSearch(1L, "older")
        repo.recordSearch(1L, "newer")

        store.refresh()

        assertEquals(listOf("newer", "older"), store.recent(10))
    }

    @Test
    fun record_promotesInCacheSynchronously_andPersists(): Unit = runBlocking {
        store.refresh()
        store.record("first")
        store.record("second")
        store.record("first")

        assertEquals(listOf("first", "second"), store.recent(10))
        assertEquals(listOf("first", "second"), repo.recentSearches(1L))
    }

    @Test
    fun delete_removesFromCacheSynchronously_andPersists(): Unit = runBlocking {
        store.record("stay")
        store.record("go")

        store.delete("go")

        assertEquals(listOf("stay"), store.recent(10))
        assertEquals(listOf("stay"), repo.recentSearches(1L))
    }

    @Test
    fun matching_prefixFilters_caseInsensitive_excludingExactInput() {
        store.record("Touhou fumo")
        store.record("touhou")
        store.record("other")

        assertEquals(listOf("Touhou fumo"), store.matching("touhou", 10))
        assertEquals(listOf("touhou", "Touhou fumo"), store.matching("tou", 10))
    }

    @Test
    fun recent_respectsLimit() {
        for (i in 1..15) store.record("q$i")
        assertEquals(10, store.recent(10).size)
        assertEquals("q15", store.recent(10).first())
    }

    @Test
    fun blankRecord_isIgnored() {
        store.record("   ")
        assertEquals(emptyList<String>(), store.recent(10))
    }
}
