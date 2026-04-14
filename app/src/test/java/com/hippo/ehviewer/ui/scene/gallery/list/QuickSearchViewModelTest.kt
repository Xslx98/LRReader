package com.hippo.ehviewer.ui.scene.gallery.list

import android.content.Context
import androidx.collection.LruCache
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.FavoritesRepository
import com.hippo.ehviewer.dao.HistoryRepository
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.dao.QuickSearchRepository
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.module.CoroutineModule
import com.hippo.ehviewer.module.IDataModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [QuickSearchViewModel].
 *
 * Uses Robolectric + in-memory Room database. ServiceRegistry is set up with
 * a test IDataModule that provides a QuickSearchRepository backed by the
 * in-memory database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class QuickSearchViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var eventScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        // Still needed for any EhDB calls that remain in the delegation chain
        val field = EhDB::class.java.getDeclaredField("sDatabase")
        field.isAccessible = true
        field.set(EhDB, db)

        // Set up ServiceRegistry with a test DataModule providing the repository
        ServiceRegistry.initializeForTest(
            coroutine = CoroutineModule(),
            data = object : IDataModule {
                override val quickSearchRepository get() =
                    QuickSearchRepository(db.browsingDao())
                override val favoritesRepository get() =
                    FavoritesRepository(db.browsingDao())
                override val historyRepository get() =
                    HistoryRepository(db.browsingDao())
                override val profileRepository get() =
                    ProfileRepository(db.miscDao())
                override val downloadDbRepository get() =
                    throw NotImplementedError("Not needed for QuickSearchViewModel tests")
                override val downloadManager: DownloadManager
                    get() = throw NotImplementedError("Not needed for QuickSearchViewModel tests")
                override val favouriteStatusRouter get() = FavouriteStatusRouter()
                override val galleryDetailCache get() = LruCache<Long, GalleryDetail>(10)
                override val spiderInfoCache: SimpleDiskCache
                    get() = throw NotImplementedError("Not needed for QuickSearchViewModel tests")
                override fun clearGalleryDetailCache() {}
            }
        )

        eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    private fun awaitCondition(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    private fun insertQuickSearch(name: String, time: Long): QuickSearch {
        return runBlocking {
            val qs = QuickSearch().apply {
                this.name = name
                this.time = time
            }
            qs.id = db.browsingDao().insertQuickSearch(qs)
            qs
        }
    }

    // ── loadQuickSearches ──────────────────────────────────────────

    @Test
    fun loadQuickSearches_populatesList() {
        insertQuickSearch("Search A", time = 100)
        insertQuickSearch("Search B", time = 200)

        val vm = QuickSearchViewModel()
        vm.loadQuickSearches()

        awaitCondition { vm.quickSearches.value.size == 2 }
        assertEquals(2, vm.quickSearches.value.size)
    }

    @Test
    fun loadQuickSearches_emptyDatabase_returnsEmptyList() {
        val vm = QuickSearchViewModel()
        vm.loadQuickSearches()

        // Give coroutine time to complete; result should still be empty
        awaitCondition { true }
        assertTrue("Should be empty on fresh DB", vm.quickSearches.value.isEmpty())
    }

    // ── deleteQuickSearch ──────────────────────────────────────────

    @Test
    fun deleteQuickSearch_removesItemAndEmitsDeletedEvent() {
        insertQuickSearch("Keep", time = 100)
        val qs2 = insertQuickSearch("Delete Me", time = 200)

        val vm = QuickSearchViewModel()
        vm.loadQuickSearches()
        awaitCondition { vm.quickSearches.value.size == 2 }

        val events = CopyOnWriteArrayList<QuickSearchViewModel.QuickSearchUiEvent>()
        eventScope.launch { vm.uiEvent.collect { events.add(it) } }

        vm.deleteQuickSearch(qs2)
        awaitCondition { vm.quickSearches.value.size == 1 }

        assertEquals("Keep", vm.quickSearches.value[0].name)
        awaitCondition { events.isNotEmpty() }
        assertTrue("Should emit Deleted event",
            events.any { it is QuickSearchViewModel.QuickSearchUiEvent.Deleted })
    }

    // ── moveQuickSearch ────────────────────────────────────────────

    @Test
    fun moveQuickSearch_reordersListCorrectly() {
        insertQuickSearch("A", time = 100)
        insertQuickSearch("B", time = 200)
        insertQuickSearch("C", time = 300)

        val vm = QuickSearchViewModel()
        vm.loadQuickSearches()
        awaitCondition { vm.quickSearches.value.size == 3 }

        // Move item from position 0 to position 2
        vm.moveQuickSearch(0, 2)

        // In-memory list is updated synchronously
        val names = vm.quickSearches.value.map { it.name }
        assertEquals(listOf("B", "C", "A"), names)
    }

    @Test
    fun moveQuickSearch_samePosition_noChange() {
        insertQuickSearch("A", time = 100)
        insertQuickSearch("B", time = 200)

        val vm = QuickSearchViewModel()
        vm.loadQuickSearches()
        awaitCondition { vm.quickSearches.value.size == 2 }

        val before = vm.quickSearches.value.map { it.name }
        vm.moveQuickSearch(0, 0)
        val after = vm.quickSearches.value.map { it.name }

        assertEquals("List should not change for same-position move", before, after)
    }

    @Test
    fun moveQuickSearch_outOfBounds_noChange() {
        insertQuickSearch("A", time = 100)

        val vm = QuickSearchViewModel()
        vm.loadQuickSearches()
        awaitCondition { vm.quickSearches.value.size == 1 }

        val sizeBefore = vm.quickSearches.value.size
        vm.moveQuickSearch(0, 5) // out of bounds
        val sizeAfter = vm.quickSearches.value.size

        assertEquals("List should not change for out-of-bounds move", sizeBefore, sizeAfter)
    }
}
