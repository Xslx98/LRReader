package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior tests for [SearchHistoryRepository] against a real in-memory Room DB.
 *
 * Locks the decisions from the search-history triage (issue #6/#12): record on
 * non-empty dispatch only, exact-match dedupe promotes to top, 50-entry cap per
 * profile with oldest eviction, per-profile isolation, and profile-deletion
 * cascade. A deterministic injected clock makes recency ordering assertable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class SearchHistoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SearchHistoryRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repo = SearchHistoryRepository(db.browsingDao(), db, now = { ++now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun record_insertsOneRow_blankAndNonPositiveProfileIgnored() = runBlocking {
        repo.recordSearch(1L, "touhou")
        repo.recordSearch(1L, "   ")
        repo.recordSearch(1L, "")
        repo.recordSearch(0L, "ignored")
        repo.recordSearch(-3L, "ignored")

        assertEquals(listOf("touhou"), repo.recentSearches(1L))
    }

    @Test
    fun record_trimsQueryBeforeStoring() = runBlocking {
        repo.recordSearch(1L, "  artist:alice  ")
        assertEquals(listOf("artist:alice"), repo.recentSearches(1L))
    }

    @Test
    fun rerecord_promotesToTop_withoutDuplicating() = runBlocking {
        repo.recordSearch(1L, "first")
        repo.recordSearch(1L, "second")
        repo.recordSearch(1L, "first")

        assertEquals(listOf("first", "second"), repo.recentSearches(1L))
    }

    @Test
    fun fiftyFirstDistinctQuery_evictsTheOldest() = runBlocking {
        for (i in 1..50) repo.recordSearch(1L, "query-$i")
        repo.recordSearch(1L, "query-51")

        val recent = repo.recentSearches(1L, limit = 100)
        assertEquals(50, recent.size)
        assertEquals("query-51", recent.first())
        assertTrue("oldest entry must be evicted", "query-1" !in recent)
        assertTrue("query-2" in recent)
    }

    @Test
    fun profiles_areIsolated() = runBlocking {
        repo.recordSearch(1L, "profile-one-search")
        repo.recordSearch(2L, "profile-two-search")

        assertEquals(listOf("profile-one-search"), repo.recentSearches(1L))
        assertEquals(listOf("profile-two-search"), repo.recentSearches(2L))
    }

    @Test
    fun deleteForProfile_cascades_onlyThatProfile() = runBlocking {
        repo.recordSearch(1L, "keep-me-not")
        repo.recordSearch(2L, "survivor")

        repo.deleteAllForProfile(1L)

        assertEquals(emptyList<String>(), repo.recentSearches(1L))
        assertEquals(listOf("survivor"), repo.recentSearches(2L))
    }

    @Test
    fun deleteEntry_removesOnlyThatEntry() = runBlocking {
        repo.recordSearch(1L, "stay")
        repo.recordSearch(1L, "go")

        repo.deleteEntry(1L, "go")

        assertEquals(listOf("stay"), repo.recentSearches(1L))
    }

    @Test
    fun recentSearches_defaultLimit_isTen() = runBlocking {
        for (i in 1..15) repo.recordSearch(1L, "q$i")

        val recent = repo.recentSearches(1L)
        assertEquals(10, recent.size)
        assertEquals("q15", recent.first())
    }

    @Test
    fun matchingSearches_prefixMatch_excludesOtherProfiles() = runBlocking {
        repo.recordSearch(1L, "touhou fumo")
        repo.recordSearch(1L, "touhou")
        repo.recordSearch(1L, "other")
        repo.recordSearch(2L, "touhou other-profile")

        val matches = repo.matchingSearches(1L, "touhou")
        assertEquals(listOf("touhou", "touhou fumo"), matches)
    }
}
