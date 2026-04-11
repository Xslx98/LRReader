package com.hippo.ehviewer.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.lanraragi.reader.client.api.LRRAuthManager
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.module.CoroutineModule
import com.hippo.lib.yorozuya.collect.LongList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.lang.ref.WeakReference

/**
 * Unit tests for [DownloadScheduler] -- worker scheduling, state machine,
 * and event dispatch.
 *
 * Uses Robolectric for Android Context + an in-memory Room database so
 * [DownloadRepository.persistInfo] can write through to a real DB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadSchedulerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var testScope: CoroutineScope
    private lateinit var repo: DownloadRepository
    private lateinit var eventBus: DownloadEventBus
    private lateinit var speedTracker: DownloadSpeedTracker
    private lateinit var scheduler: DownloadScheduler

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
            context.getSharedPreferences("lrr_auth_scheduler_test", Context.MODE_PRIVATE)
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

        // Create real collaborators
        repo = DownloadRepository(context, testScope, Dispatchers.Unconfined)
        eventBus = DownloadEventBus()

        speedTracker = DownloadSpeedTracker(object : DownloadSpeedTracker.Callback {
            override fun getFirstActiveTask(): DownloadInfo? {
                return if (scheduler.activeTasks.isEmpty()) null else scheduler.activeTasks[0]
            }

            override fun getInfoListForLabel(label: String?): List<DownloadInfo>? {
                return repo.getInfoListForLabel(label)
            }

            override fun getDownloadListener(): DownloadListener? {
                return eventBus.getDownloadListener()
            }

            override fun getDownloadInfoListeners(): List<WeakReference<DownloadInfoListener>> {
                return eventBus.getInfoListenerRefs()
            }

            override fun getWaitList(): List<DownloadInfo> {
                return scheduler.waitList
            }
        })

        scheduler = DownloadScheduler(context, testScope, repo, eventBus, speedTracker)

        // Initialize repo collections so getInfoListForLabel works
        repo.publishLoadedData(
            DownloadRepository.LoadedDownloadData(
                labels = emptyList(),
                extraSavedLabels = emptyList(),
                labelStrings = emptySet(),
                allInfoList = emptyList(),
                labelToInfoList = mapOf(null as String? to mutableListOf())
            )
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        ShadowLooper.idleMainLooper()
        db.close()
        LRRAuthManager.clear()
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private fun makeInfo(gid: Long, label: String? = null): DownloadInfo {
        val gi = GalleryInfo()
        gi.gid = gid
        gi.token = "token_$gid"
        gi.title = "Test Gallery $gid"
        val info = DownloadInfo(gi)
        info.label = label
        info.state = DownloadInfo.STATE_WAIT
        info.time = System.currentTimeMillis() - gid // ensure distinct times
        // Add to repo so getDownloadInfo works
        repo.addInfo(info)
        return info
    }

    // ═══════════════════════════════════════════════════════════
    // Tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun isIdle_initiallyTrue() {
        assertTrue("Scheduler should be idle initially", scheduler.isIdle)
    }

    @Test
    fun waitList_addAndStop() {
        val info = makeInfo(1L)
        scheduler.waitList.add(info)

        assertFalse("Scheduler should not be idle with wait list entry", scheduler.isIdle)

        val stopped = scheduler.stopDownload(1L)
        assertNotNull("stopDownload should return the stopped info", stopped)
        assertEquals(DownloadInfo.STATE_NONE, stopped!!.state)
        assertTrue("Wait list should be empty after stop", scheduler.waitList.isEmpty())
        assertTrue("Scheduler should be idle after stop", scheduler.isIdle)
    }

    @Test
    fun stopAllDownload_clearsWaitList() {
        makeInfo(1L).also { scheduler.waitList.add(it) }
        makeInfo(2L).also { scheduler.waitList.add(it) }
        makeInfo(3L).also { scheduler.waitList.add(it) }

        assertEquals(3, scheduler.waitList.size)

        scheduler.stopAllDownload()

        assertTrue("Wait list should be empty after stopAll", scheduler.waitList.isEmpty())
        assertTrue("Scheduler should be idle after stopAll", scheduler.isIdle)
    }

    @Test
    fun getNoneDownloadInfo_stopsActiveAndReturns() {
        val info = makeInfo(1L)
        scheduler.waitList.add(info)

        val result = scheduler.getNoneDownloadInfo(1L)
        assertNotNull("getNoneDownloadInfo should return the info", result)
        assertEquals(DownloadInfo.STATE_NONE, result!!.state)
        assertTrue("Wait list should be empty", scheduler.waitList.isEmpty())
    }

    @Test
    fun stopDownload_nonExistentReturnsNull() {
        val result = scheduler.stopDownload(999L)
        assertNull("Stopping a non-existent gid should return null", result)
    }

    @Test
    fun stopRangeDownload_stopsMultiple() {
        for (i in 1L..5L) {
            makeInfo(i).also { scheduler.waitList.add(it) }
        }
        assertEquals(5, scheduler.waitList.size)

        val gidList = LongList()
        gidList.add(2L)
        gidList.add(4L)
        scheduler.stopRangeDownload(gidList)

        assertEquals(3, scheduler.waitList.size)
        val remainingGids = scheduler.waitList.map { it.gid }.toSet()
        assertTrue("Should still have gid 1", 1L in remainingGids)
        assertTrue("Should still have gid 3", 3L in remainingGids)
        assertTrue("Should still have gid 5", 5L in remainingGids)
    }

    @Test
    fun ensureDownload_promotesWaitToActive() {
        val info = makeInfo(1L)
        scheduler.waitList.add(info)

        scheduler.ensureDownload()

        // Assert BEFORE idleMainLooper: ensureDownload synchronously adds to
        // activeTasks and starts the worker. In Robolectric, the worker may
        // fail immediately on Dispatchers.IO (no real server), posting an
        // OnFinish event to the main handler. Draining the looper would
        // dispatch that event and remove the info from activeTasks.
        assertTrue("Wait list should be empty after ensure", scheduler.waitList.isEmpty())
        assertEquals("Active tasks should have 1 entry", 1, scheduler.activeTasks.size)
        assertEquals(info, scheduler.activeTasks[0])
        assertEquals(DownloadInfo.STATE_DOWNLOAD, info.state)
        assertTrue("Active workers should contain the info", scheduler.activeWorkers.containsKey(info))
    }

    @Test
    fun ensureDownload_respectsConcurrencyLimit() {
        // Default concurrency limit is 1 (from DownloadSettings default).
        // Add 5 items to wait list
        for (i in 1L..5L) {
            makeInfo(i).also { scheduler.waitList.add(it) }
        }

        scheduler.ensureDownload()
        // Assert before draining looper — see ensureDownload_promotesWaitToActive comment.

        // With default concurrency of 1, only 1 should be active
        val maxConcurrent = com.hippo.ehviewer.settings.DownloadSettings.getConcurrentDownloads()
        assertEquals(
            "Active tasks should equal concurrency limit",
            maxConcurrent,
            scheduler.activeTasks.size
        )
        assertEquals(
            "Remaining wait list should be 5 minus active",
            5 - maxConcurrent,
            scheduler.waitList.size
        )
    }

    @Test
    fun stopCurrentDownload_stopsAllActive() {
        val info = makeInfo(1L)
        scheduler.waitList.add(info)
        scheduler.ensureDownload()
        // Assert before draining looper — see ensureDownload_promotesWaitToActive comment.

        assertFalse("Should not be idle with active task", scheduler.isIdle)

        scheduler.stopCurrentDownload()

        assertTrue("Active tasks should be empty after stopCurrent", scheduler.activeTasks.isEmpty())
        assertTrue("Active workers should be empty after stopCurrent", scheduler.activeWorkers.isEmpty())
    }

    @Test
    fun dispatchEvent_onFinish_removesFromActive() {
        val info = makeInfo(1L)
        // Manually add to activeTasks to simulate an in-progress download
        scheduler.activeTasks.add(info)
        info.state = DownloadInfo.STATE_DOWNLOAD

        // Dispatch an OnFinish event where all pages succeeded
        scheduler.dispatchEvent(
            DownloadScheduler.DownloadEvent.OnFinish(
                taskInfo = info,
                finished = 10,
                downloaded = 10,
                total = 10
            )
        )
        ShadowLooper.idleMainLooper()

        assertTrue("Active tasks should be empty after OnFinish", scheduler.activeTasks.isEmpty())
        assertFalse("Active workers should not contain the info", scheduler.activeWorkers.containsKey(info))
        assertEquals("State should be FINISH when legacy == 0", DownloadInfo.STATE_FINISH, info.state)
        assertEquals(10, info.finished)
        assertEquals(10, info.downloaded)
        assertEquals(10, info.total)
        assertEquals(0, info.legacy)
    }
}
