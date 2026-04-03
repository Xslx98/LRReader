package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.LinkedList

/**
 * Unit tests for [DownloadSpeedTracker].
 * Uses a fake [DownloadSpeedTracker.Callback] so no Android framework is required.
 */
class DownloadSpeedTrackerTest {

    private lateinit var activeTask: DownloadInfo
    private val infoListeners = mutableListOf<DownloadManager.DownloadInfoListener>()
    private var capturedDownloadInfo: DownloadInfo? = null
    private val waitList = LinkedList<DownloadInfo>()

    private val fakeListener = object : DownloadManager.DownloadListener {
        override fun onGet509() {}
        override fun onStart(info: DownloadInfo) {}
        override fun onDownload(info: DownloadInfo) { capturedDownloadInfo = info }
        override fun onGetPage(info: DownloadInfo) {}
        override fun onFinish(info: DownloadInfo) {}
        override fun onCancel(info: DownloadInfo) {}
    }

    private lateinit var tracker: DownloadSpeedTracker

    @Before
    fun setUp() {
        activeTask = DownloadInfo()
        activeTask.total = 100
        activeTask.downloaded = 0
        activeTask.finished = 0

        val callback = object : DownloadSpeedTracker.Callback {
            override fun getFirstActiveTask(): DownloadInfo = activeTask
            override fun getInfoListForLabel(label: String?): List<DownloadInfo>? = null
            override fun getDownloadListener(): DownloadManager.DownloadListener = fakeListener
            override fun getDownloadInfoListeners(): List<DownloadManager.DownloadInfoListener> = infoListeners
            override fun getWaitList(): LinkedList<DownloadInfo> = waitList
        }

        tracker = DownloadSpeedTracker(callback)
    }

    @Test
    fun onDownload_accumulatesBytesRead() {
        tracker.onDownload(0, 1000L, 500L, 200)
        tracker.onDownload(1, 2000L, 1000L, 300)
        // run() divides mBytesRead by 2 for smoothing: (200+300)/2 = 250
        tracker.run()
        assertEquals(250L, activeTask.speed)
    }

    @Test
    fun run_notifiesDownloadListener() {
        tracker.onDownload(0, 1000L, 500L, 100)
        tracker.run()
        assertSame(activeTask, capturedDownloadInfo)
    }

    @Test
    fun run_clearesBytesReadAfterTick() {
        tracker.onDownload(0, 1000L, 0L, 400)
        tracker.run() // speed = 400/2 = 200
        // Second tick with no new bytes: speed approaches 0 via lerp
        tracker.run()
        assertTrue("Speed should decrease toward zero", activeTask.speed < 200L)
    }

    @Test
    fun onDone_removesEntryFromMaps() {
        tracker.onDownload(0, 1000L, 0L, 500)
        tracker.onDone(0)
        tracker.run()
        // No downloading pages → remaining calculation skipped, speed is 500/2=250 from bytes
        assertEquals(250L, activeTask.speed)
        // Maps are empty after onDone: downloadingCount=0 → remaining calculation skipped,
        // stays at its initial value (0L — not set to -1 by tracker in this path)
        assertEquals(0L, activeTask.remaining)
    }

    @Test
    fun onFinish_clearsMaps() {
        tracker.onDownload(0, 1000L, 0L, 100)
        tracker.onDownload(1, 2000L, 0L, 200)
        tracker.onFinish()
        // After onFinish, content length maps are cleared; run() should still work
        tracker.run()
        // Maps empty after onFinish: downloadingCount=0 → remaining calculation skipped,
        // stays at initial 0L (not set to -1 by tracker when speed is nonzero)
        assertEquals(0L, activeTask.remaining)
    }

    @Test
    fun remainingTime_calculatedWhenSpeedNonZero() {
        // 100 total pages, 0 downloaded, 1 page downloading with 1000 bytes at 0 received
        activeTask.total = 100
        activeTask.downloaded = 0
        tracker.onDownload(0, 1000L, 0L, 2000)
        tracker.run()
        // speed = 2000/2 = 1000 B/s
        assertEquals(1000L, activeTask.speed)
        // remaining should be calculated (not -1 or max-days)
        assertTrue("Remaining should be positive", activeTask.remaining > 0)
        assertNotEquals(300L * 24 * 60 * 60 * 1000L, activeTask.remaining)
    }

    @Test
    fun remainingTime_isMaxWhenSpeedIsZero() {
        activeTask.total = 100
        activeTask.downloaded = 0
        // No bytes downloaded → speed stays 0 after lerp
        tracker.run()
        tracker.run() // second run, oldSpeed=0, newSpeed=0
        assertEquals(300L * 24 * 60 * 60 * 1000L, activeTask.remaining)
    }
}
