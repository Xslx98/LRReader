package com.hippo.ehviewer.ui.scene.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.HistoryRepository
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.dao.SearchHistoryRepository
import com.hippo.ehviewer.dao.ServerProfile
import com.hippo.ehviewer.module.CoroutineModule
import com.hippo.ehviewer.module.IDataModule
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReadingStatsViewModel] against a populated in-memory Room DB (issue #18
 * acceptance): totals, per-server breakdown with resolved names, fully
 * offline (repositories only — no client involved at all).
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
            data = object : IDataModule {
                override val searchHistoryRepository get() = SearchHistoryRepository(db.browsingDao(), db)
                override val historyRepository get() = HistoryRepository(db.archiveLocalStateDao(), db)
                override val profileRepository get() = ProfileRepository(db.miscDao())
                override val profileLookupCache get() = throw NotImplementedError("not needed")
                override val quickSearchRepository get() = throw NotImplementedError("not needed")
                override val favoritesRepository get() = throw NotImplementedError("not needed")
                override val downloadDbRepository get() = throw NotImplementedError("not needed")
                override val downloadManager get() = throw NotImplementedError("not needed")
                override val favouriteStatusRouter get() = throw NotImplementedError("not needed")
                override val archiveDetailCache get() = throw NotImplementedError("not needed")
                override val spiderInfoCache get() = throw NotImplementedError("not needed")
                override fun clearArchiveDetailCache() {}
            }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun archive(arcid: String, profileId: Long, pagecount: Int, progress: Int) = Archive(
        arcid = arcid, title = "t-$arcid", tags = emptyMap(), pagecount = pagecount,
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

        val vm = ReadingStatsViewModel()
        vm.load()
        // load() hops to Dispatchers.IO; poll until the Unconfined resume lands.
        // Poll the exact asserted condition (isLoading reset happens AFTER the
        // stats emission) — polling stats alone races the final assert.
        val deadline = System.currentTimeMillis() + 5_000
        while ((vm.stats.value == null || vm.isLoading.value) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }

        val stats = vm.stats.value!!
        assertEquals(2, stats.totalArchives)
        assertEquals(1, stats.completedCount)
        assertEquals(15L, stats.totalPagesRead)
        assertEquals("Home", stats.perServer.single().serverName)
        assertEquals(false, vm.isLoading.value)
    }
}
