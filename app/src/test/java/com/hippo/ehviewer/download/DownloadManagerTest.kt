package com.hippo.ehviewer.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.lanraragi.reader.client.api.LRRAuthManager
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.module.CoroutineModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DownloadManager] — the core download state management class.
 *
 * Uses Robolectric for Android Context + an in-memory Room database injected
 * into [EhDB] via reflection to avoid the AppDatabase singleton cache and
 * the Settings dependency in [EhDB.initialize].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var manager: DownloadManager
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Initialize Settings (needed by DownloadSettings accessed from DownloadManager)
        Settings.initialize(context)

        // Initialize CoroutineModule for ServiceRegistry (needed by DownloadManager default scope)
        ServiceRegistry.initializeForTest(CoroutineModule())

        // Create a test scope that runs on the unconfined dispatcher for synchronous execution
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // Initialize LRRAuthManager — KeyStore unavailable under Robolectric,
        // but sActiveProfileId defaults to 0 which is fine for our tests.
        LRRAuthManager.initialize(context)
        // In Robolectric, EncryptedSharedPreferences always fails — inject plain prefs.
        // The method is 'internal' in Kotlin, so its JVM name may be mangled.
        // Search by prefix to handle both plain and mangled names.
        val method = LRRAuthManager::class.java.declaredMethods.first {
            it.name.startsWith("initializeForTesting") &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == android.content.SharedPreferences::class.java
        }
        method.isAccessible = true
        method.invoke(
            null,
            context.getSharedPreferences("lrr_auth_test", Context.MODE_PRIVATE)
        )

        // Create in-memory Room database with synchronous executors so that
        // `suspend` DAO methods (which Room normally dispatches to its query
        // executor) resume on the calling thread. Combined with the
        // `Dispatchers.Unconfined` test scope below, this means the IO phase of
        // [DownloadManager.loadDataFromDb] completes on the test main thread,
        // which lets the main-thread publish step run inline (no looper drain
        // required) and lets `awaitInit` return immediately.
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        // Inject into EhDB via reflection (bypass EhDB.initialize which has Settings dependency)
        val dbField = EhDB::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(EhDB, db)

        // Configure a fake server URL so LRRDownloadWorker can be constructed
        // (ensureDownload creates workers that check for a non-null server URL)
        LRRAuthManager.setServerUrl("http://localhost:3000")

        // Drain any pending Handler callbacks from prior tests before creating manager
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Create the manager under test with test scope and wait for async init to complete
        manager = DownloadManager(context, testScope)
        kotlinx.coroutines.runBlocking { manager.awaitInitAsync() }

        // Drain the Handler.post from loadDataFromDb() listener notification
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        // Cancel all pending coroutines launched by DownloadManager (DB writes, etc.)
        // to prevent async operations from leaking into the next test.
        testScope.cancel()
        // Drain any pending Handler callbacks (e.g., from async reload)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        db.close()
        LRRAuthManager.clear()
    }

    // ═══════════════════════════════════════════════════════════
    // A. Construction & Data Loading
    // ═══════════════════════════════════════════════════════════

    @Test
    fun constructor_loadsEmptyListFromEmptyDb() {
        // Manager was created in setUp with an empty DB
        assertTrue(manager.allDownloadInfoList.isEmpty())
        assertEquals(0, manager.downloadInfoList.size)
    }

    @Test
    fun constructor_loadsExistingDownloads() {
        // Insert downloads into DB before constructing a new manager
        val info1 = DownloadInfo().apply {
            gid = 1001L
            token = "token1"
            title = "Gallery One"
            state = DownloadInfo.STATE_NONE
            time = System.currentTimeMillis()
        }
        val info2 = DownloadInfo().apply {
            gid = 1002L
            token = "token2"
            title = "Gallery Two"
            state = DownloadInfo.STATE_FINISH
            time = System.currentTimeMillis() + 1
        }
        runBlocking {
            EhDB.putDownloadInfoAsync(info1)
            EhDB.putDownloadInfoAsync(info2)
        }

        val freshManager = DownloadManager(context, testScope)
        kotlinx.coroutines.runBlocking { freshManager.awaitInitAsync() }

        assertEquals(2, freshManager.allDownloadInfoList.size)
        assertTrue(freshManager.containDownloadInfo(1001L))
        assertTrue(freshManager.containDownloadInfo(1002L))
    }

    @Test
    fun constructor_loadsExistingLabels() {
        // Insert labels into DB before constructing a new manager
        runBlocking {
            EhDB.addDownloadLabelAsync("Comics")
            EhDB.addDownloadLabelAsync("Manga")
        }

        val freshManager = DownloadManager(context, testScope)
        kotlinx.coroutines.runBlocking { freshManager.awaitInitAsync() }

        val labels = freshManager.labelList.map { it.label }
        assertTrue("Comics" in labels)
        assertTrue("Manga" in labels)
    }

    // ═══════════════════════════════════════════════════════════
    // B. Download Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Test
    fun startDownload_addsInfoToListAndNotifies() {
        val notifications = mutableListOf<String>()
        manager.addDownloadInfoListener(object : FakeDownloadInfoListener() {
            override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                notifications.add("add:${info.gid}")
            }
        })

        val gallery = GalleryInfo().apply {
            gid = 2001L
            token = "tok_2001"
            title = "Test Download"
        }
        // Start download with null (default) label
        manager.startDownload(gallery, null)

        assertTrue(manager.containDownloadInfo(2001L))
        // ensureDownload() may immediately promote WAIT -> DOWNLOAD
        val state = manager.getDownloadState(2001L)
        assertTrue(
            "Expected WAIT or DOWNLOAD, got $state",
            state == DownloadInfo.STATE_WAIT || state == DownloadInfo.STATE_DOWNLOAD
        )
        assertTrue(notifications.contains("add:2001"))
    }

    @Test
    fun startDownload_existingDownload_restartsIt() {
        // Add a download first with STATE_NONE via addDownload
        val gallery = GalleryInfo().apply {
            gid = 2002L
            token = "tok_2002"
            title = "Existing"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)
        assertEquals(DownloadInfo.STATE_NONE, manager.getDownloadState(2002L))

        // Now start it — should set to WAIT (ensureDownload may promote to DOWNLOAD)
        manager.startDownload(gallery, null)
        val state = manager.getDownloadState(2002L)
        assertTrue(
            "Expected WAIT or DOWNLOAD after restart, got $state",
            state == DownloadInfo.STATE_WAIT || state == DownloadInfo.STATE_DOWNLOAD
        )
    }

    @Test
    fun deleteDownload_removesFromAllLists() {
        val gallery = GalleryInfo().apply {
            gid = 2003L
            token = "tok_2003"
            title = "To Delete"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)
        assertTrue(manager.containDownloadInfo(2003L))

        manager.deleteDownload(2003L)

        assertFalse(manager.containDownloadInfo(2003L))
        assertNull(manager.getDownloadInfo(2003L))
    }

    @Test
    fun stopAllDownload_stopsAllActive() {
        // Add some downloads in WAIT state
        for (i in 1..3) {
            val gallery = GalleryInfo().apply {
                gid = (3000 + i).toLong()
                token = "tok_$i"
                title = "Gallery $i"
            }
            manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)
        }

        // Put them into wait state via startDownload
        for (i in 1..3) {
            val gallery = GalleryInfo().apply {
                gid = (3000 + i).toLong()
                token = "tok_$i"
                title = "Gallery $i"
            }
            manager.startDownload(gallery, null)
        }

        manager.stopAllDownload()

        for (i in 1..3) {
            val state = manager.getDownloadState((3000 + i).toLong())
            assertTrue(
                "Expected STATE_NONE or STATE_DOWNLOAD after stop, got $state",
                state == DownloadInfo.STATE_NONE || state == DownloadInfo.STATE_DOWNLOAD
            )
        }
    }

    @Test
    fun getDownloadState_returnsCorrectState() {
        // Non-existent returns INVALID
        assertEquals(DownloadInfo.STATE_INVALID, manager.getDownloadState(9999L))

        // Added returns the correct state
        val gallery = GalleryInfo().apply {
            gid = 2005L
            token = "tok_2005"
            title = "State Test"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_FINISH)
        assertEquals(DownloadInfo.STATE_FINISH, manager.getDownloadState(2005L))
    }

    // ═══════════════════════════════════════════════════════════
    // C. Label Management
    // ═══════════════════════════════════════════════════════════

    @Test
    fun addLabel_createsNewLabelInDbAndList() {
        manager.addLabel("NewLabel")

        val labels = manager.labelList.map { it.label }
        assertTrue("NewLabel" in labels)

        // With Unconfined dispatcher, DB write completes synchronously
        // but give a tiny grace period for coroutine dispatch
        Thread.sleep(100)

        // Verify in DB
        val dbLabels = runBlocking { EhDB.getAllDownloadLabelListAsync() }
        assertTrue(dbLabels.any { it.label == "NewLabel" })
    }

    @Test
    fun renameLabel_updatesInfoLabels() {
        manager.addLabel("OldName")

        // Add a download with that label
        val gallery = GalleryInfo().apply {
            gid = 4001L
            token = "tok_4001"
            title = "Labeled"
        }
        manager.addDownload(gallery, "OldName", DownloadInfo.STATE_NONE)

        // Rename the label
        manager.renameLabel("OldName", "NewName")

        // Verify label list updated
        val labels = manager.labelList.map { it.label }
        assertFalse("OldName" in labels)
        assertTrue("NewName" in labels)

        // Verify the download's label was updated
        val info = manager.getDownloadInfo(4001L)
        assertEquals("NewName", info?.label)
    }

    @Test
    fun removeLabel_movesInfoToDefault() {
        manager.addLabel("ToRemove")

        // Add a download with that label
        val gallery = GalleryInfo().apply {
            gid = 4002L
            token = "tok_4002"
            title = "Will Move"
        }
        manager.addDownload(gallery, "ToRemove", DownloadInfo.STATE_NONE)

        // Delete the label
        manager.deleteLabel("ToRemove")

        // Verify label removed
        val labels = manager.labelList.map { it.label }
        assertFalse("ToRemove" in labels)

        // Verify the download moved to default (null label)
        val info = manager.getDownloadInfo(4002L)
        assertNull("Download should have null label (default)", info?.label)

        // Verify it's in the default list
        assertTrue(manager.defaultDownloadInfoList.any { it.gid == 4002L })
    }

    @Test
    fun getLabelList_returnsAllLabels() {
        manager.addLabel("Alpha")
        manager.addLabel("Beta")
        manager.addLabel("Gamma")

        val labels = manager.labelList.map { it.label }
        assertEquals(3, labels.size)
        assertTrue("Alpha" in labels)
        assertTrue("Beta" in labels)
        assertTrue("Gamma" in labels)
    }

    // ═══════════════════════════════════════════════════════════
    // D. Listener Notifications
    // ═══════════════════════════════════════════════════════════

    @Test
    fun addDownloadInfoListener_receivesCallbacks() {
        val events = mutableListOf<String>()
        val listener = object : FakeDownloadInfoListener() {
            override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                events.add("add:${info.gid}")
            }
            override fun onUpdateLabels() {
                events.add("updateLabels")
            }
        }
        manager.addDownloadInfoListener(listener)

        val gallery = GalleryInfo().apply {
            gid = 5001L
            token = "tok_5001"
            title = "Listener Test"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)
        manager.addLabel("ListenerLabel")

        assertTrue("add:5001" in events)
        assertTrue("updateLabels" in events)
    }

    @Test
    fun removeDownloadInfoListener_stopsReceiving() {
        val events = mutableListOf<String>()
        val listener = object : FakeDownloadInfoListener() {
            override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                events.add("add:${info.gid}")
            }
        }
        manager.addDownloadInfoListener(listener)

        // First add — should be received
        val gallery1 = GalleryInfo().apply {
            gid = 5002L
            token = "tok_5002"
            title = "First"
        }
        manager.addDownload(gallery1, null, DownloadInfo.STATE_NONE)
        assertEquals(1, events.size)

        // Remove listener
        manager.removeDownloadInfoListener(listener)

        // Second add — should NOT be received
        val gallery2 = GalleryInfo().apply {
            gid = 5003L
            token = "tok_5003"
            title = "Second"
        }
        manager.addDownload(gallery2, null, DownloadInfo.STATE_NONE)
        assertEquals(1, events.size) // Still 1 — listener was removed
    }

    @Test
    fun reload_clearsAndReloadsFromDb() {
        // Insert a download directly to DB (bypassing manager) so we have
        // guaranteed data to reload from.
        val info = DownloadInfo().apply {
            gid = 5004L
            token = "tok_5004"
            title = "Reload Test"
            state = DownloadInfo.STATE_NONE
            time = System.currentTimeMillis()
        }
        runBlocking { EhDB.putDownloadInfoAsync(info) }

        val events = mutableListOf<String>()
        manager.addDownloadInfoListener(object : FakeDownloadInfoListener() {
            override fun onReload() {
                events.add("reload")
            }
            override fun onUpdateAll() {
                events.add("updateAll")
            }
        })

        // Reload — should pick up the DB-inserted download
        manager.reload()

        // Wait for async reload to complete and process Handler callbacks
        Thread.sleep(200)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // After reload, our download should be present (DB may also contain
        // data from prior tests since the in-memory DB is shared in the suite)
        assertTrue(
            "allDownloadInfoList should not be empty after reload",
            manager.allDownloadInfoList.isNotEmpty()
        )
        assertTrue(manager.containDownloadInfo(5004L))
        assertTrue("reload" in events)
    }

    // ═══════════════════════════════════════════════════════════
    // E. Query Methods
    // ═══════════════════════════════════════════════════════════

    @Test
    fun containDownloadInfo_returnsTrueForExisting() {
        assertFalse(manager.containDownloadInfo(6001L))

        val gallery = GalleryInfo().apply {
            gid = 6001L
            token = "tok_6001"
            title = "Contain Test"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)

        assertTrue(manager.containDownloadInfo(6001L))
    }

    @Test
    fun getDownloadInfo_returnsCorrectInfo() {
        assertNull(manager.getDownloadInfo(6002L))

        val gallery = GalleryInfo().apply {
            gid = 6002L
            token = "tok_6002"
            title = "Info Test"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)

        val info = manager.getDownloadInfo(6002L)
        assertNotNull(info)
        assertEquals(6002L, info!!.gid)
        assertEquals("Info Test", info.title)
    }

    @Test
    fun getDownloadCount_returnsCorrectCount() {
        assertEquals(0, manager.allDownloadInfoList.size)

        for (i in 1..5) {
            val gallery = GalleryInfo().apply {
                gid = (6100 + i).toLong()
                token = "tok_$i"
                title = "Count $i"
            }
            manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)
        }

        assertEquals(5, manager.allDownloadInfoList.size)
        assertEquals(5, manager.downloadInfoList.size)
    }

    // ═══════════════════════════════════════════════════════════
    // Helper: Fake DownloadInfoListener with no-op defaults
    // ═══════════════════════════════════════════════════════════

    // ===============================================================
    // F. Thread Safety -- assertMainThread coverage
    // ===============================================================

    @Test
    fun publicMethods_throwOnBackgroundThread() {
        val methodsToCheck: List<Pair<String, () -> Unit>> = listOf(
            "containLabel" to { manager.containLabel("x") },
            "containDownloadInfo" to { manager.containDownloadInfo(0L) },
            "labelList" to { manager.labelList },
            "getLabelCount" to { manager.getLabelCount(null) },
            "allDownloadInfoList" to { manager.allDownloadInfoList },
            "defaultDownloadInfoList" to { manager.defaultDownloadInfoList },
            "getLabelDownloadInfoList" to { manager.getLabelDownloadInfoList(null) },
            "downloadInfoList" to { manager.downloadInfoList },
            "getDownloadInfo" to { manager.getDownloadInfo(0L) },
            "getDownloadState" to { manager.getDownloadState(0L) },
            "addDownloadInfoListener" to { manager.addDownloadInfoListener(FakeDownloadInfoListener()) },
            "removeDownloadInfoListener" to { manager.removeDownloadInfoListener(FakeDownloadInfoListener()) },
            "setDownloadListener" to { manager.setDownloadListener(null) },
            "isIdle" to { manager.isIdle },
            "resetAllReadingProgress" to { manager.resetAllReadingProgress() },
        )

        for ((name, block) in methodsToCheck) {
            var thrown: Throwable? = null
            val t = Thread {
                try {
                    block()
                } catch (e: Throwable) {
                    thrown = e
                }
            }
            t.start()
            t.join(5000)
            assertTrue(
                "$name should throw IllegalStateException on background thread, but threw: $thrown",
                thrown is IllegalStateException
            )
        }
    }

    @Test
    fun awaitInitAsync_throwsOnMainThread() {
        // Use a real IO dispatcher so init is not already complete by the time
        // we try to await it from the main thread.
        val lazyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val uninitManager = DownloadManager(context, lazyScope)
        try {
            assertThrows(IllegalStateException::class.java) {
                // Robolectric runs tests on the main looper, so launching a
                // runBlocking here executes awaitInitAsync on the main thread
                // and the guard inside the suspend function must fire.
                kotlinx.coroutines.runBlocking {
                    uninitManager.awaitInitAsync()
                }
            }
        } finally {
            lazyScope.cancel()
        }
    }

    /**
     * Race regression test for W0-1.
     *
     * The pre-fix [DownloadManager.loadDataFromDb] wrote directly into the
     * main-thread-only collections (mLabelList, mLabelSet, mMap, ...) from the
     * IO coroutine. With a real [Dispatchers.IO] scope this races with any
     * main-thread reader (such as `containLabel`), and would intermittently
     * surface as `ConcurrentModificationException`, partial-state reads, or
     * `HashMap` data corruption.
     *
     * Each iteration constructs a fresh manager backed by a real IO dispatcher,
     * spams the public read API while the IO phase is in flight, drains the
     * main looper to let the new publish phase post run, then waits for init
     * and asserts the published state is complete.
     *
     * The fix guarantees that the IO phase only touches the database; all
     * shared-collection writes happen via [DownloadManager.runOnMainThread] on
     * the test main thread. With the fix this test runs 1000 iterations
     * without throwing.
     */
    @Test
    fun loadDataFromDb_concurrentReads_isStable_underBackgroundIo() {
        // Pre-populate the DB once.
        val labelCount = 50
        val infoCount = 200
        val labelStrings = (0 until labelCount).map { "race-label-$it" }
        runBlocking {
            for (s in labelStrings) {
                EhDB.addDownloadLabelAsync(s)
            }
            for (i in 0 until infoCount) {
                val info = DownloadInfo().apply {
                    gid = 90000L + i
                    token = "race-token-$i"
                    title = "race title $i"
                    label = labelStrings[i % labelCount]
                    state = DownloadInfo.STATE_NONE
                    time = System.currentTimeMillis() + i
                }
                EhDB.putDownloadInfoAsync(info)
            }
        }

        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            repeat(1000) { iteration ->
                val freshManager = DownloadManager(context, ioScope)

                // Spam main-thread reads while the IO phase is in flight on the
                // background dispatcher. None of these should throw and none
                // should observe a partially-mutated collection.
                for (spin in 0 until 50) {
                    try {
                        // Snapshot copies — exercises iteration of the live list.
                        val labelsSnapshot = freshManager.labelList.toList()
                        // Existence checks — exercises HashSet read concurrent
                        // with what used to be a HashSet write.
                        freshManager.containLabel(labelStrings[spin % labelCount])
                        freshManager.containDownloadInfo(90000L + spin)
                        // Sanity assertion: snapshot must never be partially
                        // populated. It is either empty (publish hasn't run) or
                        // exactly the full label set.
                        val size = labelsSnapshot.size
                        assertTrue(
                            "iter=$iteration spin=$spin: unexpected partial labelList size $size",
                            size == 0 || size == labelCount
                        )
                    } catch (e: ConcurrentModificationException) {
                        fail("iter=$iteration spin=$spin: ConcurrentModificationException $e")
                    }
                    if (spin % 5 == 0) {
                        org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    }
                }

                // Drain the publish post and confirm init completed cleanly.
                // We cannot use runBlocking { awaitInitAsync() } here because
                // it would block the main thread, preventing the main looper
                // from processing the SimpleHandler.post dispatched by the IO
                // coroutine — a deadlock. Instead, poll: drain the looper then
                // sleep briefly until the publish phase has landed.
                var initDone = false
                for (attempt in 0 until 500) {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    if (freshManager.labelList.size == labelCount) { initDone = true; break }
                    Thread.sleep(1)
                }
                assertTrue("iter=$iteration: init did not complete within timeout", initDone)
                assertEquals(
                    "iter=$iteration: published label count mismatch",
                    labelCount,
                    freshManager.labelList.size
                )
                assertEquals(
                    "iter=$iteration: published info count mismatch",
                    infoCount,
                    freshManager.allDownloadInfoList.size
                )
            }
        } finally {
            ioScope.cancel()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun collections_arePlainTypes_notConcurrent() {
        val fields = DownloadManager::class.java.declaredFields
        val concurrentTypes = listOf(
            "CopyOnWriteArrayList",
            "ConcurrentHashMap",
            "ConcurrentLinkedQueue",
            "ConcurrentSkipListMap"
        )
        for (field in fields) {
            val typeName = field.type.simpleName
            assertFalse(
                "Field '${field.name}' should not use concurrent collection type $typeName",
                concurrentTypes.any { typeName.contains(it) }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // G. Sort Order Invariants (binary-insertion correctness)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun addDownloadBatch_producesDateDescSortedAllList() {
        // Add items with deliberately non-sorted timestamps
        val timestamps = listOf(500L, 100L, 900L, 300L, 700L)
        val infos = timestamps.mapIndexed { i, ts ->
            DownloadInfo().apply {
                gid = (7000 + i).toLong()
                token = "tok_sort_$i"
                title = "Sort Test $i"
                state = DownloadInfo.STATE_NONE
                time = ts
            }
        }
        manager.addDownload(infos)
        // Drain the handler post from addDownload
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val allList = manager.allDownloadInfoList
        assertEquals(5, allList.size)
        // Verify DATE_DESC order: newest first
        for (i in 0 until allList.size - 1) {
            assertTrue(
                "allDownloadInfoList not in DATE_DESC order at index $i: " +
                    "${allList[i].time} should >= ${allList[i + 1].time}",
                allList[i].time >= allList[i + 1].time
            )
        }
        // Verify exact expected order by gid
        assertEquals(listOf(7002L, 7004L, 7000L, 7003L, 7001L), allList.map { it.gid })
    }

    @Test
    fun addDownloadBatch_producesDateDescSortedPerLabelList() {
        manager.addLabel("SortLabel")

        val timestamps = listOf(200L, 800L, 400L, 600L)
        val infos = timestamps.mapIndexed { i, ts ->
            DownloadInfo().apply {
                gid = (7100 + i).toLong()
                token = "tok_lsort_$i"
                title = "Label Sort $i"
                label = "SortLabel"
                state = DownloadInfo.STATE_NONE
                time = ts
            }
        }
        manager.addDownload(infos)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val labelList = manager.getLabelDownloadInfoList("SortLabel")!!
        assertEquals(4, labelList.size)
        for (i in 0 until labelList.size - 1) {
            assertTrue(
                "Per-label list not in DATE_DESC order at index $i",
                labelList[i].time >= labelList[i + 1].time
            )
        }
    }

    @Test
    fun changeLabel_maintainsSortOrder() {
        manager.addLabel("SourceLabel")
        manager.addLabel("DestLabel")

        // Add items to DestLabel with known timestamps
        val destInfos = listOf(900L, 300L).mapIndexed { i, ts ->
            DownloadInfo().apply {
                gid = (7200 + i).toLong()
                token = "tok_dest_$i"
                title = "Dest $i"
                label = "DestLabel"
                state = DownloadInfo.STATE_NONE
                time = ts
            }
        }
        manager.addDownload(destInfos)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Add an item to SourceLabel that should land between the two dest items
        val sourceInfo = DownloadInfo().apply {
            gid = 7210L
            token = "tok_src"
            title = "Source Item"
            label = "SourceLabel"
            state = DownloadInfo.STATE_NONE
            time = 600L
        }
        manager.addDownload(listOf(sourceInfo))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Move the source item to DestLabel
        manager.changeLabel(listOf(manager.getDownloadInfo(7210L)!!), "DestLabel")

        val destList = manager.getLabelDownloadInfoList("DestLabel")!!
        assertEquals(3, destList.size)
        for (i in 0 until destList.size - 1) {
            assertTrue(
                "DestLabel list not in DATE_DESC order at index $i: " +
                    "${destList[i].time} should >= ${destList[i + 1].time}",
                destList[i].time >= destList[i + 1].time
            )
        }
        // Verify the moved item landed in the middle (time=600 between 900 and 300)
        assertEquals(7210L, destList[1].gid)
    }

    @Test
    fun deleteLabel_maintainsSortOrderInDefaultList() {
        manager.addLabel("ToDelete")

        // Add items to default list with known timestamps
        val defaultGallery = GalleryInfo().apply {
            gid = 7300L
            token = "tok_def"
            title = "Default Item"
        }
        manager.addDownload(defaultGallery, null, DownloadInfo.STATE_NONE)
        // Manually set time for deterministic ordering
        manager.getDownloadInfo(7300L)!!.time = 500L

        // Add items to the label that will be deleted
        val labelInfos = listOf(800L, 200L).mapIndexed { i, ts ->
            DownloadInfo().apply {
                gid = (7310 + i).toLong()
                token = "tok_del_$i"
                title = "Delete Label Item $i"
                label = "ToDelete"
                state = DownloadInfo.STATE_NONE
                time = ts
            }
        }
        manager.addDownload(labelInfos)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Delete the label — items should merge into default list in sorted order
        manager.deleteLabel("ToDelete")

        val defaultList = manager.defaultDownloadInfoList
        assertEquals(3, defaultList.size)
        for (i in 0 until defaultList.size - 1) {
            assertTrue(
                "Default list not in DATE_DESC order at index $i: " +
                    "${defaultList[i].time} should >= ${defaultList[i + 1].time}",
                defaultList[i].time >= defaultList[i + 1].time
            )
        }
    }

    @Test
    fun insertSorted_handlesEqualTimestamps() {
        // Add items with the same timestamp — should not crash or corrupt order
        val infos = (0..4).map { i ->
            DownloadInfo().apply {
                gid = (7400 + i).toLong()
                token = "tok_eq_$i"
                title = "Equal Time $i"
                state = DownloadInfo.STATE_NONE
                time = 1000L // all same timestamp
            }
        }
        manager.addDownload(infos)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val allList = manager.allDownloadInfoList
        assertEquals(5, allList.size)
        // All timestamps equal — just verify no crash and all items present
        val gids = allList.map { it.gid }.toSet()
        assertEquals(setOf(7400L, 7401L, 7402L, 7403L, 7404L), gids)
    }

    @Test
    fun insertSorted_companionHelper_correctInsertionPoints() {
        // Directly test the companion insertSorted helper
        val list = mutableListOf<DownloadInfo>()

        // Insert in random order and verify sorted after each
        val timestamps = listOf(500L, 900L, 100L, 700L, 300L)
        for ((i, ts) in timestamps.withIndex()) {
            val info = DownloadInfo().apply {
                gid = (7500 + i).toLong()
                token = "tok_helper_$i"
                title = "Helper $i"
                time = ts
            }
            DownloadManager.insertSorted(list, info)

            // After every insertion, list must be in DATE_DESC order
            for (j in 0 until list.size - 1) {
                assertTrue(
                    "List not sorted after inserting time=$ts at step $i, index $j: " +
                        "${list[j].time} should >= ${list[j + 1].time}",
                    list[j].time >= list[j + 1].time
                )
            }
        }

        assertEquals(5, list.size)
        // Expected order: 900, 700, 500, 300, 100
        assertEquals(listOf(900L, 700L, 500L, 300L, 100L), list.map { it.time })
    }

    private open class FakeDownloadInfoListener : DownloadInfoListener {
        override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {}
        override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {}
        override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>, mWaitList: List<DownloadInfo>) {}
        override fun onUpdateAll() {}
        override fun onReload() {}
        override fun onChange() {}
        override fun onRenameLabel(from: String, to: String) {}
        override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {}
        override fun onUpdateLabels() {}
    }
}
