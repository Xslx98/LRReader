/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.download

import android.content.Context
import android.os.Looper
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.lib.image.Image
import com.hippo.lib.yorozuya.collect.LongList
import kotlinx.coroutines.CoroutineScope

/**
 * Owns worker scheduling, concurrency management, and the download event
 * dispatch loop.
 *
 * Extracted from [DownloadManager] so that worker lifecycle, wait/active
 * state transitions, and event routing have a single, focused owner.
 *
 * All public methods must be called on the main thread (enforced via
 * [assertMainThread]).
 */
internal class DownloadScheduler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: DownloadRepository,
    private val eventBus: DownloadEventBus,
    private val speedTracker: DownloadSpeedTracker
) {

    /** Downloads queued to start. */
    internal val waitList: MutableList<DownloadInfo> = ArrayList()

    /** Currently downloading. */
    internal val activeTasks: MutableList<DownloadInfo> = ArrayList()

    /** Worker instances keyed by their DownloadInfo. */
    internal val activeWorkers: MutableMap<DownloadInfo, LRRDownloadWorker> = HashMap()

    // ═══════════════════════════════════════════════════════════
    // Thread safety
    // ═══════════════════════════════════════════════════════════

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DownloadScheduler method must be called on the main thread, current: ${Thread.currentThread().name}"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // State queries
    // ═══════════════════════════════════════════════════════════

    val isIdle: Boolean
        get() {
            assertMainThread()
            return activeTasks.isEmpty() && waitList.isEmpty()
        }

    // ═══════════════════════════════════════════════════════════
    // Worker lifecycle
    // ═══════════════════════════════════════════════════════════

    /**
     * Promotes downloads from [waitList] to [activeTasks] up to the
     * concurrency limit. Creates an [LRRDownloadWorker] for each promoted
     * task, wires up the [PerTaskListener], persists state, and starts the
     * worker.
     */
    fun ensureDownload() {
        assertMainThread()
        val maxConcurrent = DownloadSettings.getConcurrentDownloads()
        while (activeTasks.size < maxConcurrent && waitList.isNotEmpty()) {
            val info = waitList.removeAt(0)
            val worker = LRRDownloadWorker(context, info)
            activeTasks.add(info)
            activeWorkers[info] = worker
            worker.listener = PerTaskListener(info)
            info.state = DownloadInfo.STATE_DOWNLOAD
            info.speed = -1
            info.remaining = -1
            info.total = -1
            info.finished = 0
            info.downloaded = 0
            info.legacy = -1
            // Persist to DB
            repo.persistInfo(info)
            // Start speed tracking
            speedTracker.start()
            // Notify start downloading
            eventBus.getDownloadListener()?.onStart(info)
            // Notify state update
            val list = repo.getInfoListForLabel(info.label)
            if (list != null) {
                eventBus.forEachListener { it.onUpdate(info, list, waitList) }
            }
            // Start the worker
            worker.start()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Stop operations
    // ═══════════════════════════════════════════════════════════

    /**
     * Stop a single download by gid. Checks active tasks first, then wait
     * list. Updates state to [DownloadInfo.STATE_NONE], persists, and
     * notifies the download listener on cancel (for active tasks).
     *
     * @return the stopped [DownloadInfo], or null if not found.
     */
    fun stopDownload(gid: Long): DownloadInfo? {
        assertMainThread()
        // Check active tasks
        val activeIt = activeTasks.iterator()
        while (activeIt.hasNext()) {
            val info = activeIt.next()
            if (info.gid == gid) {
                val w = activeWorkers.remove(info)
                w?.cancel()
                activeIt.remove()
                if (activeTasks.isEmpty()) speedTracker.stop()
                info.state = DownloadInfo.STATE_NONE
                repo.persistInfo(info)
                eventBus.getDownloadListener()?.onCancel(info)
                return info
            }
        }

        // Check wait list
        val waitIt = waitList.iterator()
        while (waitIt.hasNext()) {
            val info = waitIt.next()
            if (info.gid == gid) {
                waitIt.remove()
                info.state = DownloadInfo.STATE_NONE
                repo.persistInfo(info)
                return info
            }
        }
        return null
    }

    /**
     * Stop all currently active downloads. Cancels all workers, clears
     * active tasks/workers, stops speed tracking.
     *
     * @return the first stopped [DownloadInfo], or null if none were active.
     */
    fun stopCurrentDownload(): DownloadInfo? {
        assertMainThread()
        // Cancel all active workers
        for (w in activeWorkers.values) {
            w.cancel()
        }
        val stopped = ArrayList(activeTasks)
        activeTasks.clear()
        activeWorkers.clear()
        speedTracker.stop()
        if (stopped.isEmpty()) return null
        for (info in stopped) {
            info.state = DownloadInfo.STATE_NONE
            repo.persistInfo(info)
            eventBus.getDownloadListener()?.onCancel(info)
        }
        return stopped[0]
    }

    /**
     * Stop a range of downloads by gid list. Stops both active and waiting
     * downloads.
     */
    fun stopRangeDownload(gidList: LongList) {
        assertMainThread()
        if (gidList.size() < waitList.size) {
            for (i in 0 until gidList.size()) {
                stopDownload(gidList.get(i))
            }
        } else {
            // Check active tasks
            for (active in ArrayList(activeTasks)) {
                if (gidList.contains(active.gid)) {
                    stopDownload(active.gid)
                }
            }
            // Check all in wait list
            val iterator = waitList.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                if (gidList.contains(info.gid)) {
                    iterator.remove()
                    info.state = DownloadInfo.STATE_NONE
                    repo.persistInfo(info)
                }
            }
        }
    }

    /**
     * Stop all downloads: clear the wait list and stop all active downloads.
     */
    fun stopAllDownload() {
        assertMainThread()
        // Stop all in wait list
        for (info in waitList) {
            info.state = DownloadInfo.STATE_NONE
            repo.persistInfo(info)
        }
        waitList.clear()
        // Stop current
        stopCurrentDownload()
    }

    // ═══════════════════════════════════════════════════════════
    // Query + stop combo
    // ═══════════════════════════════════════════════════════════

    /**
     * If the given gid is currently downloading (active or waiting), stop it
     * and set its state to [DownloadInfo.STATE_NONE]. Returns the
     * [DownloadInfo] from the repository (may be null if not tracked).
     */
    fun getNoneDownloadInfo(gid: Long): DownloadInfo? {
        assertMainThread()
        var wasActive = false
        for (info in activeTasks) {
            if (info.gid == gid) {
                wasActive = true
                break
            }
        }
        if (wasActive) {
            stopDownload(gid)
        } else {
            val iterator = waitList.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                if (info.gid == gid) {
                    info.state = DownloadInfo.STATE_NONE
                    iterator.remove()
                    break
                }
            }
        }
        return repo.getDownloadInfo(gid)
    }

    // ═══════════════════════════════════════════════════════════
    // Event system
    // ═══════════════════════════════════════════════════════════

    /**
     * Sealed interface for immutable download events dispatched from worker
     * threads. Replaces the mutable NotifyTask + ConcurrentPool pattern.
     */
    internal sealed interface DownloadEvent {
        data class OnGetPages(val taskInfo: DownloadInfo, val pages: Int) : DownloadEvent
        data object OnGet509 : DownloadEvent
        data class OnPageDownload(
            val index: Int,
            val contentLength: Long,
            val receivedSize: Long,
            val bytesRead: Int
        ) : DownloadEvent
        data class OnPageSuccess(
            val taskInfo: DownloadInfo,
            val index: Int,
            val finished: Int,
            val downloaded: Int,
            val total: Int
        ) : DownloadEvent
        data class OnPageFailure(
            val taskInfo: DownloadInfo,
            val index: Int,
            val error: String?,
            val finished: Int,
            val downloaded: Int,
            val total: Int
        ) : DownloadEvent
        data class OnFinish(
            val taskInfo: DownloadInfo,
            val finished: Int,
            val downloaded: Int,
            val total: Int
        ) : DownloadEvent
    }

    /**
     * Dispatch a download event on the main thread. Handles each event type
     * by updating state, notifying listeners, and triggering follow-up
     * actions (e.g., starting the next download on finish).
     */
    internal fun dispatchEvent(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.OnGetPages -> {
                val info = event.taskInfo
                info.total = event.pages
                val list = repo.getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener {
                        it.onUpdate(info, list, waitList)
                    }
                }
            }
            is DownloadEvent.OnGet509 -> {
                eventBus.getDownloadListener()?.onGet509()
            }
            is DownloadEvent.OnPageDownload -> {
                speedTracker.onDownload(event.index, event.contentLength, event.receivedSize, event.bytesRead)
            }
            is DownloadEvent.OnPageSuccess -> {
                speedTracker.onDone(event.index)
                val info = event.taskInfo
                info.finished = event.finished
                info.downloaded = event.downloaded
                info.total = event.total
                eventBus.getDownloadListener()?.onGetPage(info)
                val list = repo.getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener {
                        it.onUpdate(info, list, waitList)
                    }
                }
            }
            is DownloadEvent.OnPageFailure -> {
                speedTracker.onDone(event.index)
                val info = event.taskInfo
                info.finished = event.finished
                info.downloaded = event.downloaded
                info.total = event.total
                val list = repo.getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener {
                        it.onUpdate(info, list, waitList)
                    }
                }
            }
            is DownloadEvent.OnFinish -> {
                speedTracker.onFinish()
                val info = event.taskInfo
                activeTasks.remove(info)
                activeWorkers.remove(info)
                if (activeTasks.isEmpty()) speedTracker.stop()
                // Update state
                info.finished = event.finished
                info.downloaded = event.downloaded
                info.total = event.total
                info.legacy = event.total - event.finished
                if (info.legacy == 0) {
                    info.state = DownloadInfo.STATE_FINISH
                } else {
                    info.state = DownloadInfo.STATE_FAILED
                }
                // Persist to DB
                repo.persistInfo(info)
                // Notify
                eventBus.getDownloadListener()?.onFinish(info)
                val list = repo.getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener {
                        it.onUpdate(info, list, waitList)
                    }
                }
                // Start next download
                ensureDownload()
            }
        }
    }

    /**
     * Post a [DownloadEvent] to the main thread for dispatch.
     */
    private fun postEvent(event: DownloadEvent) {
        eventBus.postToMain { dispatchEvent(event) }
    }

    // ═══════════════════════════════════════════════════════════
    // Per-task listener (bridges worker callbacks to events)
    // ═══════════════════════════════════════════════════════════

    /**
     * Bridges [SpiderQueen.OnSpiderListener] callbacks from
     * [LRRDownloadWorker] into the [DownloadEvent] sealed interface,
     * posting them to the main thread for dispatch.
     */
    internal inner class PerTaskListener(private val mInfo: DownloadInfo) : SpiderQueen.OnSpiderListener {

        override fun onGetPages(pages: Int) {
            postEvent(DownloadEvent.OnGetPages(mInfo, pages))
        }

        override fun onGet509(index: Int) {
            postEvent(DownloadEvent.OnGet509)
        }

        override fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
            postEvent(DownloadEvent.OnPageDownload(index, contentLength, receivedSize, bytesRead))
        }

        override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) {
            postEvent(DownloadEvent.OnPageSuccess(mInfo, index, finished, downloaded, total))
        }

        override fun onPageFailure(index: Int, error: String, finished: Int, downloaded: Int, total: Int) {
            postEvent(DownloadEvent.OnPageFailure(mInfo, index, error, finished, downloaded, total))
        }

        override fun onFinish(finished: Int, downloaded: Int, total: Int) {
            postEvent(DownloadEvent.OnFinish(mInfo, finished, downloaded, total))
        }

        override fun onGetImageSuccess(index: Int, image: Image) {
            // Not used by download scheduler
        }

        override fun onGetImageFailure(index: Int, error: String) {
            // Not used by download scheduler
        }
    }
}
