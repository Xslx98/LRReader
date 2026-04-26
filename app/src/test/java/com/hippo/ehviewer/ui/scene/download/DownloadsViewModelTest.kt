package com.hippo.ehviewer.ui.scene.download

import android.content.Context
import androidx.collection.LruCache
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.FavouriteStatusRouter
import java.io.File
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.mapper.toArchive
import com.hippo.ehviewer.client.LRRUtils
import com.lanraragi.reader.domain.ArchiveDetail
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.DownloadDbRepository
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.module.CoroutineModule
import com.hippo.ehviewer.module.IDataModule
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Unit tests for [DownloadsViewModel] — download list state, label switching,
 * search, category filter, pagination math, spider info cache, and
 * DownloadInfoListener -> sealed DownloadUiEvent forwarding.
 *
 * Uses Robolectric for Android Context + in-memory Room database. The
 * DownloadManager is constructed with a test CoroutineScope so that async
 * initialisation completes synchronously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadsViewModelTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var testScope: CoroutineScope
    private lateinit var vm: DownloadsViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        Settings.initialize(context)
        ServiceRegistry.initializeForTest(CoroutineModule())

        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        LRRAuthManager.initialize(context)
        val method = LRRAuthManager::class.java.declaredMethods.first {
            it.name.startsWith("initializeForTesting") &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == android.content.SharedPreferences::class.java
        }
        method.isAccessible = true
        method.invoke(
            null,
            context.getSharedPreferences("lrr_auth_vm_test", Context.MODE_PRIVATE)
        )

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        val dbField = EhDB::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(EhDB, db)

        LRRAuthManager.setServerUrl("http://localhost:3000")

        ShadowLooper.idleMainLooper()

        // Set up DataModule BEFORE constructing DownloadManager, because
        // DownloadRepository (in-memory layer) now calls
        // ServiceRegistry.dataModule.downloadDbRepository during init.
        // Use lateinit to break the circular dependency: DataModule needs
        // manager, but manager needs DataModule to exist during construction.
        lateinit var manager: DownloadManager
        ServiceRegistry.initializeForTest(
            data = object : IDataModule {
                override val downloadManager: DownloadManager get() = manager
                override val favouriteStatusRouter get() = FavouriteStatusRouter()
                override val historyRepository get() =
                    com.hippo.ehviewer.dao.HistoryRepository(db.browsingDao())
                override val profileRepository get() = ProfileRepository(db.miscDao())
                override val quickSearchRepository get() =
                    com.hippo.ehviewer.dao.QuickSearchRepository(db.browsingDao())
                override val favoritesRepository get() =
                    com.hippo.ehviewer.dao.FavoritesRepository(db.browsingDao())
                override val downloadDbRepository get() =
                    DownloadDbRepository(db.downloadDao(), db)
                override val archiveDetailCache get() = LruCache<String, ArchiveDetail>(10)
                override val spiderInfoCache: SimpleDiskCache get() =
                    SimpleDiskCache(
                        java.io.File(context.cacheDir, "test_spider_info"),
                        1024 * 1024
                    )
                override fun clearArchiveDetailCache() {}
            }
        )

        // Now create the manager — its internal DownloadRepository will
        // find downloadDbRepository via ServiceRegistry.
        manager = DownloadManager(context, testScope)
        runBlocking { manager.awaitInitAsync() }
        ShadowLooper.idleMainLooper()

        vm = DownloadsViewModel()
    }

    @After
    fun tearDown() {
        testScope.cancel()
        ShadowLooper.idleMainLooper()
        db.close()
        LRRAuthManager.clear()
    }

    // ═══════════════════════════════════════════════════════════
    // A. Initial state
    // ═══════════════════════════════════════════════════════════

    @Test
    fun initialState_downloadListIsEmpty() {
        assertTrue(vm.downloadList.value.isEmpty())
    }

    @Test
    fun initialState_searchingIsFalse() {
        assertFalse(vm.searching.value)
    }

    @Test
    fun initialState_searchKeyIsNull() {
        assertNull(vm.searchKey.value)
    }

    @Test
    fun initialState_indexPageIsOne() {
        assertEquals(1, vm.indexPage.value)
    }

    @Test
    fun initialState_pageSizeIsOne() {
        assertEquals(1, vm.pageSize.value)
    }

    @Test
    fun initialState_selectedCategoryIsAll() {
        assertEquals(LRRUtils.ALL_CATEGORY, vm.selectedCategory.value)
    }

    @Test
    fun initialState_filterLoadingIsFalse() {
        assertFalse(vm.filterLoading.value)
    }

    // ═══════════════════════════════════════════════════════════
    // B. Label switching
    // ═══════════════════════════════════════════════════════════

    @Test
    fun selectLabel_updatesCurrentLabelState() {
        vm.selectLabel("My Label")
        assertEquals("My Label", vm.currentLabel.value)
    }

    @Test
    fun selectLabel_null_resetsToDefault() {
        vm.selectLabel("Some Label")
        vm.selectLabel(null)
        assertNull(vm.currentLabel.value)
    }

    @Test
    fun handleLabelRenamed_updatesCurrentLabel_whenMatching() {
        vm.selectLabel("Old Name")
        vm.handleLabelRenamed("Old Name", "New Name")
        assertEquals("New Name", vm.currentLabel.value)
    }

    @Test
    fun handleLabelRenamed_doesNotChange_whenNotMatching() {
        vm.selectLabel("Other Label")
        vm.handleLabelRenamed("Old Name", "New Name")
        assertEquals("Other Label", vm.currentLabel.value)
    }

    @Test
    fun resetToDefaultLabel_setsCurrentLabelToNull() {
        vm.selectLabel("Some Label")
        vm.resetToDefaultLabel()
        assertNull(vm.currentLabel.value)
    }

    // ═══════════════════════════════════════════════════════════
    // C. Search state
    // ═══════════════════════════════════════════════════════════

    @Test
    fun setSearchKey_updatesState() {
        vm.setSearchKey("test query")
        assertEquals("test query", vm.searchKey.value)
    }

    @Test
    fun setSearching_updatesState() {
        vm.setSearching(true)
        assertTrue(vm.searching.value)
        vm.setSearching(false)
        assertFalse(vm.searching.value)
    }

    // ═══════════════════════════════════════════════════════════
    // D. Category filter
    // ═══════════════════════════════════════════════════════════

    @Test
    fun setSelectedCategory_updatesState() {
        vm.setSelectedCategory(42)
        assertEquals(42, vm.selectedCategory.value)
    }

    @Test
    fun filterByCategory_allCategory_returnsFullBackList() {
        val info1 = DownloadInfo().apply { gid = 1L; category = 1 }
        val info2 = DownloadInfo().apply { gid = 2L; category = 2 }
        vm.setDownloadList(listOf(info1, info2))

        // Set backList via updateForLabel (which uses downloadManager default list)
        // Instead, directly test the filter logic by setting backList indirectly:
        // We must set up the download list first, then filter.
        // Use reflection to set _backList since it's private
        val backListField = DownloadsViewModel::class.java.getDeclaredField("_backList")
        backListField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (backListField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<DownloadInfo>>)
            .value = listOf(info1, info2)

        vm.setSelectedCategory(LRRUtils.ALL_CATEGORY)
        vm.filterByCategory()

        assertEquals(2, vm.downloadList.value.size)
    }

    @Test
    fun filterByCategory_specificCategory_filtersCorrectly() {
        val info1 = DownloadInfo().apply { gid = 1L; category = 1 }
        val info2 = DownloadInfo().apply { gid = 2L; category = 2 }
        val info3 = DownloadInfo().apply { gid = 3L; category = 1 }

        val backListField = DownloadsViewModel::class.java.getDeclaredField("_backList")
        backListField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (backListField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<DownloadInfo>>)
            .value = listOf(info1, info2, info3)

        vm.setSelectedCategory(1)
        vm.filterByCategory()

        assertEquals(2, vm.downloadList.value.size)
        assertTrue(vm.downloadList.value.all { it.category == 1 })
    }

    // ═══════════════════════════════════════════════════════════
    // E. Pagination math
    // ═══════════════════════════════════════════════════════════

    @Test
    fun positionInList_smallList_returnsPositionAsIs() {
        // List with fewer items than PAGINATION_SIZE (500)
        val smallList = (1..10).map { DownloadInfo().apply { gid = it.toLong() } }
        vm.setDownloadList(smallList)
        assertEquals(3, vm.positionInList(3))
    }

    @Test
    fun listIndexInPage_smallList_returnsPositionAsIs() {
        val smallList = (1..10).map { DownloadInfo().apply { gid = it.toLong() } }
        vm.setDownloadList(smallList)
        assertEquals(5, vm.listIndexInPage(5))
    }

    // ═══════════════════════════════════════════════════════════
    // F. Spider info cache
    // ═══════════════════════════════════════════════════════════

    @Test
    fun putSpiderInfo_addsToMap() {
        val spiderInfo = com.hippo.ehviewer.spider.SpiderInfo().apply {
            arcid = "abc123"
            pages = 10
        }
        vm.putSpiderInfo("abc123", spiderInfo)
        assertEquals(spiderInfo, vm.spiderInfoMap.value["abc123"])
    }

    @Test
    fun removeSpiderInfo_removesFromMap() {
        val spiderInfo = com.hippo.ehviewer.spider.SpiderInfo().apply {
            arcid = "abc123"
            pages = 10
        }
        vm.putSpiderInfo("abc123", spiderInfo)
        vm.removeSpiderInfo("abc123")
        assertNull(vm.spiderInfoMap.value["abc123"])
    }

    // ═══════════════════════════════════════════════════════════
    // G. DownloadInfoListener -> sealed event forwarding
    // ═══════════════════════════════════════════════════════════

    @Test
    fun onAdd_emitsItemAddedEvent() {
        val events = mutableListOf<DownloadUiEvent>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.downloadEvent.collect { events.add(it) }
        }

        val info = DownloadInfo().apply { gid = 1L }
        vm.onAdd(info, listOf(info), 0)

        assertTrue(events.size >= 1)
        val event = events.first()
        assertTrue(event is DownloadUiEvent.ItemAdded)
        assertEquals(1L, (event as DownloadUiEvent.ItemAdded).info.gid)

        job.cancel()
    }

    @Test
    fun onRemove_emitsItemRemovedEvent() {
        val events = mutableListOf<DownloadUiEvent>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.downloadEvent.collect { events.add(it) }
        }

        val info = DownloadInfo().apply { gid = 2L }
        vm.onRemove(info, emptyList(), 0)

        assertTrue(events.size >= 1)
        assertTrue(events.first() is DownloadUiEvent.ItemRemoved)

        job.cancel()
    }

    @Test
    fun onReload_emitsReloadedEvent() {
        val events = mutableListOf<DownloadUiEvent>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.downloadEvent.collect { events.add(it) }
        }

        vm.onReload()

        assertTrue(events.size >= 1)
        assertTrue(events.first() is DownloadUiEvent.Reloaded)

        job.cancel()
    }

    @Test
    fun onRenameLabel_emitsLabelRenamedEvent() {
        val events = mutableListOf<DownloadUiEvent>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.downloadEvent.collect { events.add(it) }
        }

        vm.onRenameLabel("old", "new")

        assertTrue(events.size >= 1)
        val event = events.first()
        assertTrue(event is DownloadUiEvent.LabelRenamed)
        assertEquals("old", (event as DownloadUiEvent.LabelRenamed).from)
        assertEquals("new", event.to)

        job.cancel()
    }

    @Test
    fun onChange_emitsLabelDeletedEvent() {
        val events = mutableListOf<DownloadUiEvent>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.downloadEvent.collect { events.add(it) }
        }

        vm.onChange()

        assertTrue(events.size >= 1)
        assertTrue(events.first() is DownloadUiEvent.LabelDeleted)

        job.cancel()
    }

    @Test
    fun onUpdateLabels_emitsLabelsChangedEvent() {
        val events = mutableListOf<DownloadUiEvent>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.downloadEvent.collect { events.add(it) }
        }

        vm.onUpdateLabels()

        assertTrue(events.size >= 1)
        assertTrue(events.first() is DownloadUiEvent.LabelsChanged)

        job.cancel()
    }

    // ═══════════════════════════════════════════════════════════
    // H. Archive format validation (via reflection)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun isValidArchiveFormat_acceptsZipCbzRarCbr() {
        val method = DownloadsViewModel::class.java.getDeclaredMethod(
            "isValidArchiveFormat", String::class.java
        )
        method.isAccessible = true

        assertTrue(method.invoke(vm, "test.zip") as Boolean)
        assertTrue(method.invoke(vm, "test.ZIP") as Boolean)
        assertTrue(method.invoke(vm, "test.cbz") as Boolean)
        assertTrue(method.invoke(vm, "test.rar") as Boolean)
        assertTrue(method.invoke(vm, "test.cbr") as Boolean)
        assertFalse(method.invoke(vm, "test.pdf") as Boolean)
        assertFalse(method.invoke(vm, "test.txt") as Boolean)
        assertFalse(method.invoke(vm, null) as Boolean)
    }

    // ═══════════════════════════════════════════════════════════
    // I. Pagination / page size state
    // ═══════════════════════════════════════════════════════════

    @Test
    fun setIndexPage_updatesState() {
        vm.setIndexPage(5)
        assertEquals(5, vm.indexPage.value)
    }

    @Test
    fun setPageSize_updatesState() {
        vm.setPageSize(100)
        assertEquals(100, vm.pageSize.value)
    }

    @Test
    fun setDownloadList_updatesState() {
        val list = listOf(
            DownloadInfo().apply { gid = 1L },
            DownloadInfo().apply { gid = 2L }
        )
        vm.setDownloadList(list)
        assertEquals(2, vm.downloadList.value.size)
    }

    // -------------------------------------------------------------------------
    // W35-3b post-mortem: split Room (structural) from progressMap (transient)
    // -------------------------------------------------------------------------

    @Test
    fun `progressMap exposes tracker snapshots by arcid`() = runBlocking {
        val dm = vm.downloadManager

        dm.progressTracker.update(
            "arc-prog-1",
            speed = 12345L,
            finished = 3,
            downloaded = 3,
            total = 10,
            remaining = 7777L
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val snap = vm.progressMap.value["arc-prog-1"]
        assertTrue("expected arc-prog-1 in progressMap", snap != null)
        assertEquals(12345L, snap!!.speed)
        assertEquals(3, snap.finished)
        assertEquals(3, snap.downloaded)
        assertEquals(10, snap.total)
        assertEquals(7777L, snap.remaining)
    }

    @Test
    fun `downloadList does not carry tracker progress fields`() = runBlocking {
        // Structural split: the Room-emitted DownloadInfo in downloadList
        // must not receive mutations from the progress tracker. UI reads
        // progress via progressMap / DownloadManager.progressFor instead.
        val dm = vm.downloadManager
        val info = DownloadInfo().apply {
            arcid = "arc-struct"
            title = "structural only"
            label = null
            state = DownloadInfo.STATE_DOWNLOAD
            time = 1_000L
        }
        dm.addDownloadInfo(info.toArchive(), null)
        ServiceRegistry.dataModule.downloadDbRepository.putDownloadInfo(info)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        dm.progressTracker.update(
            "arc-struct",
            speed = 99999L,
            finished = 7,
            downloaded = 7,
            total = 20,
            remaining = 1234L
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val emitted = vm.downloadList.value.firstOrNull { it.arcid == "arc-struct" }
        assertTrue("expected arc-struct in downloadList", emitted != null)
        // Post-W35-3c: progress lives only in progressMap. The Room-emitted
        // DownloadInfo no longer carries @Ignore progress fields at all.
        assertEquals(99999L, vm.progressMap.value["arc-struct"]?.speed)
        assertEquals(7, vm.progressMap.value["arc-struct"]?.finished)
        assertEquals(7, vm.progressMap.value["arc-struct"]?.downloaded)
    }

    @Test
    fun `downloadList filters by currentLabel`() = runBlocking {
        val dm = vm.downloadManager
        // Add one default-label and one "L1"-label info
        val infoA = DownloadInfo().apply {
            arcid = "a"; title = "A"; label = null; state = DownloadInfo.STATE_NONE; time = 1L
        }
        val infoB = DownloadInfo().apply {
            arcid = "b"; title = "B"; label = "L1"; state = DownloadInfo.STATE_NONE; time = 2L
        }
        dm.addLabel("L1")
        dm.addDownloadInfo(infoA.toArchive(), null)
        dm.addDownloadInfo(infoB.toArchive(), "L1")
        ServiceRegistry.dataModule.downloadDbRepository.putDownloadInfo(infoA)
        ServiceRegistry.dataModule.downloadDbRepository.putDownloadInfo(infoB)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Default label → sees only A
        vm.selectLabel(null)
        vm.updateForLabel()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val defaultList = vm.downloadList.value
        assertTrue(defaultList.any { it.arcid == "a" })
        assertFalse(defaultList.any { it.arcid == "b" })

        // Switch to L1 → sees only B
        vm.selectLabel("L1")
        vm.updateForLabel()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val l1List = vm.downloadList.value
        assertTrue(l1List.any { it.arcid == "b" })
        assertFalse(l1List.any { it.arcid == "a" })
    }

    @Test
    fun `progressMap re-emits every tracker update`() = runBlocking {
        val dm = vm.downloadManager
        val seen = mutableListOf<Long>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.progressMap.collect { map -> map["arc-ticks"]?.speed?.let { seen.add(it) } }
        }

        dm.progressTracker.update("arc-ticks", speed = 100L)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        dm.progressTracker.update("arc-ticks", speed = 200L)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        dm.progressTracker.update("arc-ticks", speed = 300L)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        job.cancel()
        // Initial emission (empty map) adds no entry; three updates add three speeds.
        assertEquals(listOf(100L, 200L, 300L), seen)
    }
}
