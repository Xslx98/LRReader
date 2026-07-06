package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.ref.WeakReference

/**
 * Unit tests for [DownloadSpeedTracker].
 * Uses a fake [DownloadSpeedTracker.Callback] so no Android framework is required.
 *
 * Post-W35-3c: progress fields (speed/remaining/total/downloaded/finished) live
 * exclusively on [DownloadProgressTracker]; tests read/write through it.
 */
class DownloadSpeedTrackerTest {

    private val arcid = "test-arcid"

    private lateinit var activeTask: DownloadInfo
    private lateinit var activeTasks: MutableList<DownloadInfo>
    private val infoListeners = mutableListOf<DownloadInfoListener>()
    private var capturedDownloadInfo: DownloadInfo? = null
    private val waitList = mutableListOf<DownloadInfo>()

    private val fakeListener = object : DownloadListener {
        override fun onGet509() {}
        override fun onStart(info: DownloadInfo) {}
        override fun onDownload(info: DownloadInfo) { capturedDownloadInfo = info }
        override fun onGetPage(info: DownloadInfo) {}
        override fun onFinish(info: DownloadInfo) {}
        override fun onCancel(info: DownloadInfo) {}
    }

    private lateinit var tracker: DownloadSpeedTracker
    private lateinit var progressTracker: DownloadProgressTracker

    @Before
    fun setUp() {
        activeTask = DownloadInfo()
        activeTask.arcid = arcid
        activeTasks = mutableListOf(activeTask)

        progressTracker = DownloadProgressTracker()
        progressTracker.update(arcid, total = 100, downloaded = 0, finished = 0)

        val callback = object : DownloadSpeedTracker.Callback {
            override fun getActiveTasks(): List<DownloadInfo> = activeTasks
            override fun getInfoListForLabel(label: String?): List<DownloadInfo>? = null
            override fun getDownloadListener(): DownloadListener = fakeListener
            override fun getDownloadInfoListeners(): List<WeakReference<DownloadInfoListener>> =
                infoListeners.map { WeakReference(it) }
            override fun getWaitList(): List<DownloadInfo> = waitList
        }

        tracker = DownloadSpeedTracker(callback, progressTracker)
    }

    private fun snap() = progressTracker.snapshot(arcid)!!

    @Test
    fun onDownload_accumulatesBytesRead() {
        tracker.onDownload(arcid, 0, 1000L, 500L, 200)
        tracker.onDownload(arcid, 1, 2000L, 1000L, 300)
        // run() divides the byte counter by 2 for smoothing: (200+300)/2 = 250
        tracker.run()
        assertEquals(250L, snap().speed)
    }

    @Test
    fun run_writesRemainingIntoProgressTracker() {
        progressTracker.update(arcid, total = 100, downloaded = 0)
        tracker.onDownload(arcid, 0, 1000L, 0L, 2000)
        tracker.run()
        val s = snap()
        assertTrue("Speed should be positive", s.speed > 0)
        assertTrue("Remaining should be positive", s.remaining > 0)
    }

    @Test
    fun run_notifiesDownloadListener() {
        tracker.onDownload(arcid, 0, 1000L, 500L, 100)
        tracker.run()
        assertSame(activeTask, capturedDownloadInfo)
    }

    @Test
    fun run_clearesBytesReadAfterTick() {
        tracker.onDownload(arcid, 0, 1000L, 0L, 400)
        tracker.run() // speed = 400/2 = 200
        // Second tick with no new bytes: speed approaches 0 via lerp
        tracker.run()
        assertTrue("Speed should decrease toward zero", snap().speed < 200L)
    }

    @Test
    fun onDone_removesEntryFromMaps() {
        tracker.onDownload(arcid, 0, 1000L, 0L, 500)
        tracker.onDone(arcid, 0)
        tracker.run()
        // No downloading pages → remaining calculation skipped, speed is 500/2=250 from bytes
        assertEquals(250L, snap().speed)
        // Maps are empty after onDone: downloadingCount=0 → remaining calculation skipped,
        // tracker keeps initial -1L (initial sentinel for remaining).
        assertEquals(-1L, snap().remaining)
    }

    @Test
    fun onFinish_clearsMaps() {
        tracker.onDownload(arcid, 0, 1000L, 0L, 100)
        tracker.onDownload(arcid, 1, 2000L, 0L, 200)
        tracker.onFinish(arcid)
        // After onFinish, this archive's maps are cleared; run() should still work
        tracker.run()
        // Maps empty after onFinish: downloadingCount=0 → remaining calculation skipped,
        // tracker keeps initial -1L sentinel.
        assertEquals(-1L, snap().remaining)
    }

    // ─── DL-15: per-archive slicing under concurrent downloads ───

    @Test
    fun concurrentTasks_speedAndRemainingSlicedPerArchive() {
        val arcidB = "test-arcid-b"
        val taskB = DownloadInfo().also { it.arcid = arcidB }
        activeTasks.add(taskB)
        progressTracker.update(arcidB, total = 50, downloaded = 0, finished = 0)

        // Same page index 0 in both archives — must not collide.
        tracker.onDownload(arcid, 0, 1000L, 0L, 2000)
        tracker.onDownload(arcidB, 0, 4000L, 0L, 6000)
        tracker.run()

        // Each task gets its own byte window, not the 8000-byte sum.
        assertEquals(1000L, snap().speed)
        assertEquals(3000L, progressTracker.snapshot(arcidB)!!.speed)
        assertTrue("A remaining computed", snap().remaining > 0)
        assertTrue("B remaining computed", progressTracker.snapshot(arcidB)!!.remaining > 0)
    }

    @Test
    fun onFinish_clearsOnlyThatArchivesSlice() {
        val arcidB = "test-arcid-b"
        val taskB = DownloadInfo().also { it.arcid = arcidB }
        activeTasks.add(taskB)
        progressTracker.update(arcidB, total = 50, downloaded = 0, finished = 0)

        tracker.onDownload(arcid, 0, 1000L, 0L, 100)
        tracker.onDownload(arcidB, 0, 2000L, 0L, 200)
        tracker.onFinish(arcid)
        tracker.run()

        // A's slice is gone (remaining stays at the -1L sentinel)...
        assertEquals(-1L, snap().remaining)
        // ...but B's accounting survives the other archive's finish.
        assertEquals(100L, progressTracker.snapshot(arcidB)!!.speed)
        assertTrue(progressTracker.snapshot(arcidB)!!.remaining > 0)
    }

    @Test
    fun remainingTime_calculatedWhenSpeedNonZero() {
        // 100 total pages, 0 downloaded, 1 page downloading with 1000 bytes at 0 received
        progressTracker.update(arcid, total = 100, downloaded = 0)
        tracker.onDownload(arcid, 0, 1000L, 0L, 2000)
        tracker.run()
        // speed = 2000/2 = 1000 B/s
        assertEquals(1000L, snap().speed)
        // remaining should be calculated (not -1 or max-days)
        assertTrue("Remaining should be positive", snap().remaining > 0)
        assertNotEquals(300L * 24 * 60 * 60 * 1000L, snap().remaining)
    }

    @Test
    fun remainingTime_isMaxWhenSpeedIsZero() {
        progressTracker.update(arcid, total = 100, downloaded = 0)
        // No bytes downloaded → speed stays 0 after lerp
        tracker.run()
        tracker.run() // second run, oldSpeed=0, newSpeed=0
        assertEquals(300L * 24 * 60 * 60 * 1000L, snap().remaining)
    }
}
