package com.lanraragi.reader.ui.scene.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.Settings
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.SearchHistoryRepository
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.module.CoroutineModule
import com.lanraragi.reader.module.IDataModule
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.ui.list.ListScreenState
import com.lanraragi.reader.stubAppModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReadingStatsViewModel] against a populated in-memory Room DB (issue #18
 * acceptance): totals, per-server breakdown with resolved names, fully
 * offline (repositories only — the server-exclusions provider is pinned so no
 * client is involved at all).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ReadingStatsViewModelTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx: Context = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        ServiceRegistry.initializeForTest(
            coroutine = CoroutineModule(),
            app = stubAppModule(ctx),
            data = object : IDataModule {
                override val searchHistoryRepository get() = SearchHistoryRepository(db.browsingDao(), db)
                override val historyRepository get() = HistoryRepository(db.archiveLocalStateDao(), db)
                override val profileRepository get() = ProfileRepository(db.miscDao())
                override val profileLookupCache get() = throw UnsupportedOperationException("not needed")
                override val quickSearchRepository get() = throw UnsupportedOperationException("not needed")
                override val favoritesRepository get() = throw UnsupportedOperationException("not needed")
                override val downloadDbRepository get() = throw UnsupportedOperationException("not needed")
                override val downloadManager get() = throw UnsupportedOperationException("not needed")
                override val favouriteStatusRouter get() = throw UnsupportedOperationException("not needed")
                override val archiveDetailCache get() = throw UnsupportedOperationException("not needed")
                override fun clearArchiveDetailCache() {}
            }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun archive(
        arcid: String,
        profileId: Long,
        pagecount: Int,
        progress: Int,
        tags: Map<String, List<String>> = emptyMap(),
    ) = Archive(
        arcid = arcid, title = "t-$arcid", tags = tags, pagecount = pagecount,
        progress = progress, extension = "zip", filename = "f.zip", thumbnailUrl = "",
        rating = 0f, isnew = false, lastreadtime = 100L, summary = null,
        serverProfileId = profileId,
    )

    @Test
    fun load_derivesStatsFromPopulatedDb_withProfileNames() = runBlocking {
        val historyRepo = HistoryRepository(db.archiveLocalStateDao(), db)
        val homeId = db.miscDao().insertServerProfile(
            ServerProfile(name = "Home", url = "https://home.example", isActive = true)
        )
        historyRepo.putHistoryInfo(archive("a".repeat(40), homeId, pagecount = 10, progress = 10))
        historyRepo.putHistoryInfo(archive("b".repeat(40), homeId, pagecount = 20, progress = 5))

        val vm = ReadingStatsViewModel(serverExcludedNamespaces = { emptySet() })
        vm.load()
        // load() hops to Dispatchers.IO; wait until the Unconfined resume lands.
        // Wait on the exact asserted condition (the state settles AFTER the
        // stats emission) — waiting on stats alone races the final assert.
        awaitUntil { vm.state.value !is ListScreenState.Loading }

        val stats = vm.state.value.items.single()
        assertEquals(2, stats.totalArchives)
        assertEquals(1, stats.completedCount)
        assertEquals(15L, stats.totalPagesRead)
        assertEquals("Home", stats.perServer.single().serverName)
        assertTrue(vm.state.value is ListScreenState.Content)
    }

    /** Nothing read yet is the empty screen, not a page of zeros. */
    @Test
    fun load_withNothingRead_isEmpty() {
        val vm = ReadingStatsViewModel(serverExcludedNamespaces = { emptySet() })
        vm.load()
        awaitUntil { vm.state.value !is ListScreenState.Loading }

        assertEquals(ListScreenState.Empty, vm.state.value)
    }

    /** A failed load is the error with its retry; it used to look like "nothing read yet". */
    @Test
    fun load_failure_isTheError() {
        val vm = ReadingStatsViewModel(serverExcludedNamespaces = { throw java.io.IOException("unreachable") })
        vm.load()
        awaitUntil { vm.state.value !is ListScreenState.Loading }

        assertTrue("state: ${vm.state.value}", vm.state.value is ListScreenState.Error)
    }

    @Test
    fun load_appliesServerExcludedNamespacesToTagPreference() = runBlocking {
        // The provider's result (the active server's excluded_namespaces) must
        // reach the tag-preference derivation, not just the built-in set.
        val historyRepo = HistoryRepository(db.archiveLocalStateDao(), db)
        val homeId = db.miscDao().insertServerProfile(
            ServerProfile(name = "Home", url = "https://home.example", isActive = true)
        )
        historyRepo.putHistoryInfo(
            archive(
                "a".repeat(40), homeId, pagecount = 10, progress = 10,
                tags = mapOf("artist" to listOf("alice"), "series" to listOf("touhou")),
            )
        )

        val vm = ReadingStatsViewModel(serverExcludedNamespaces = { setOf("artist") })
        vm.load()
        awaitUntil { vm.tagPreference.value != null && vm.state.value !is ListScreenState.Loading }

        val pref = vm.tagPreference.value!!
        assertEquals(emptyList<String>(), pref.artists.map { it.tag })
        assertEquals(listOf("touhou"), pref.series.map { it.tag })
    }
}
