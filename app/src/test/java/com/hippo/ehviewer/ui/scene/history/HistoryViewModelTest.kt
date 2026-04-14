package com.hippo.ehviewer.ui.scene.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.HistoryRepository
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.module.CoroutineModule
import com.hippo.ehviewer.module.IDataModule
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.robolectric.shadows.ShadowLooper

/**
 * Unit tests for [HistoryViewModel] — history list loading, delete,
 * clear, snapshot reset, and DiffUtil integration.
 *
 * Uses Robolectric for Android Context + in-memory Room database.
 * [Dispatchers.Unconfined] is set as Main so that viewModelScope.launch
 * runs eagerly. Room executors are synchronous via setQueryExecutor.
 * After each async operation we idle the looper to drain any pending posts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class HistoryViewModelTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)

        context = ApplicationProvider.getApplicationContext()

        Settings.initialize(context)
        ServiceRegistry.initializeForTest(CoroutineModule())

        LRRAuthManager.initialize(context)
        // Initialize DB early so we can wire HistoryRepository before ViewModel creation
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        val dbField = EhDB::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(EhDB, db)

        // Provide a DataModule with HistoryRepository so the ViewModel can resolve it
        ServiceRegistry.initializeForTest(
            data = object : IDataModule {
                override val historyRepository get() = HistoryRepository(db.browsingDao())
                override val profileRepository get() = ProfileRepository(db.miscDao())
                override val quickSearchRepository get() = throw NotImplementedError("not needed")
                override val favoritesRepository get() = throw NotImplementedError("not needed")
                override val downloadDbRepository get() = throw NotImplementedError("not needed")
                override val downloadManager get() = throw NotImplementedError("not needed")
                override val favouriteStatusRouter get() = throw NotImplementedError("not needed")
                override val galleryDetailCache get() = throw NotImplementedError("not needed")
                override val spiderInfoCache get() = throw NotImplementedError("not needed")
                override fun clearGalleryDetailCache() {}
            }
        )
        val method = LRRAuthManager::class.java.declaredMethods.first {
            it.name.startsWith("initializeForTesting") &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == android.content.SharedPreferences::class.java
        }
        method.isAccessible = true
        method.invoke(
            null,
            context.getSharedPreferences("lrr_auth_hist_test", Context.MODE_PRIVATE)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        LRRAuthManager.clear()
    }

    // ═══════════════════════════════════════════════════════════
    // A. Initial state
    // ═══════════════════════════════════════════════════════════

    @Test
    fun initialState_historyListIsEmpty() {
        val vm = HistoryViewModel()
        assertTrue(vm.historyList.value.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // B. Load history
    // ═══════════════════════════════════════════════════════════

    @Test
    fun loadHistory_populatesHistoryList() {
        insertGalleries(1L to "Gallery One", 2L to "Gallery Two")

        val vm = HistoryViewModel()
        vm.loadHistory()
        drainCoroutines()

        assertEquals(2, vm.historyList.value.size)
    }

    @Test
    fun loadHistory_emitsListUpdate() {
        insertGalleries(1L to "Gallery One")

        val vm = HistoryViewModel()
        val updates = mutableListOf<HistoryViewModel.ListUpdate>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.listUpdate.collect { updates.add(it) }
        }

        vm.loadHistory()
        drainCoroutines()

        assertTrue(updates.isNotEmpty())
        assertEquals(1, updates.first().newList.size)

        job.cancel()
    }

    @Test
    fun loadHistory_fromEmptyDb_resultsInEmptyList() {
        val vm = HistoryViewModel()
        vm.loadHistory()
        drainCoroutines()

        assertTrue(vm.historyList.value.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // C. Delete operations
    // ═══════════════════════════════════════════════════════════

    @Test
    fun deleteHistoryItem_removesItemAndReloads() {
        insertGalleries(1L to "Gallery One", 2L to "Gallery Two")

        val vm = HistoryViewModel()
        vm.loadHistory()
        drainCoroutines()
        assertEquals(2, vm.historyList.value.size)

        val itemToDelete = vm.historyList.value.first { it.gid == 1L }
        vm.deleteHistoryItem(itemToDelete)
        drainCoroutines()

        assertEquals(1, vm.historyList.value.size)
        assertEquals(2L, vm.historyList.value.first().gid)
    }

    @Test
    fun clearAllHistory_emptiesTheList() {
        insertGalleries(1L to "Gallery One", 2L to "Gallery Two")

        val vm = HistoryViewModel()
        vm.loadHistory()
        drainCoroutines()
        assertEquals(2, vm.historyList.value.size)

        vm.clearAllHistory()
        drainCoroutines()

        assertTrue(vm.historyList.value.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // D. Snapshot management
    // ═══════════════════════════════════════════════════════════

    @Test
    fun resetSnapshot_clearsHistoryListAndSnapshot() {
        insertGalleries(1L to "Gallery One")

        val vm = HistoryViewModel()
        vm.loadHistory()
        drainCoroutines()
        assertEquals(1, vm.historyList.value.size)

        vm.resetSnapshot()
        assertTrue(vm.historyList.value.isEmpty())
    }

    @Test
    fun resetSnapshot_thenReload_producesCleanDelta() {
        insertGalleries(1L to "Gallery One")

        val vm = HistoryViewModel()
        vm.loadHistory()
        drainCoroutines()

        vm.resetSnapshot()

        val updates = mutableListOf<HistoryViewModel.ListUpdate>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.listUpdate.collect { updates.add(it) }
        }

        vm.loadHistory()
        drainCoroutines()

        assertTrue(updates.isNotEmpty())
        assertEquals(1, updates.last().newList.size)

        job.cancel()
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Inserts galleries into the history table via the DAO directly.
     */
    private fun insertGalleries(vararg galleries: Pair<Long, String>) {
        runBlocking {
            val dao = db.browsingDao()
            for ((gid, title) in galleries) {
                val gi = GalleryInfo().apply {
                    this.gid = gid
                    this.token = "tok$gid"
                    this.title = title
                }
                val historyInfo = com.hippo.ehviewer.dao.HistoryInfo(gi)
                historyInfo.time = System.currentTimeMillis()
                dao.insertHistory(historyInfo)
            }
        }
    }

    /**
     * Drain all pending coroutine dispatches and looper callbacks so that
     * viewModelScope.launch coroutines complete before assertions.
     *
     * The HistoryViewModel uses `withContext(Dispatchers.IO)` which runs
     * on a real IO thread. We give it a brief moment to complete, then
     * idle the main looper so the continuation resumes on Main and
     * StateFlow values get published.
     */
    private fun drainCoroutines() {
        // Give IO coroutines time to complete (Room executors are sync,
        // so the actual DB work is instantaneous; we just need the IO
        // dispatcher to schedule and resume)
        Thread.sleep(100)
        ShadowLooper.idleMainLooper()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()
    }
}
