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

import android.os.Handler
import android.os.Looper
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.lib.yorozuya.MathUtils
import java.lang.ref.WeakReference

/**
 * Calculates and reports download speed and remaining time on a 2-second interval.
 * Extracted from DownloadManager to give it a single responsibility.
 *
 * Callbacks are supplied via [Callback] so this class does not need a
 * direct reference to DownloadManager.
 *
 * All accounting is sliced per arcid (DL-15): concurrent downloads share this
 * tracker, page indices repeat across archives, and a shared byte counter
 * would attribute the sum of every task's traffic to a single task.
 * Worker events and [run] both execute on the main thread, so the maps need
 * no synchronization.
 */
internal class DownloadSpeedTracker(
    private val callback: Callback,
    private val progressTracker: DownloadProgressTracker = DownloadProgressTracker()
) : Runnable {

    private val handler = Handler(Looper.getMainLooper())

    /** Supplies the data DownloadSpeedTracker needs from DownloadManager. */
    interface Callback {
        /** @return a snapshot of all currently-active tasks (empty if idle). */
        fun getActiveTasks(): List<DownloadInfo>

        /** @return the per-label list used for listener notifications. */
        fun getInfoListForLabel(label: String?): List<DownloadInfo>?

        /** @return the active DownloadListener (may be null). */
        fun getDownloadListener(): DownloadListener?

        /** @return all registered DownloadInfoListeners (weak references). */
        fun getDownloadInfoListeners(): List<WeakReference<DownloadInfoListener>>

        /** @return the current wait list (passed to onUpdate). */
        fun getWaitList(): List<DownloadInfo>
    }

    private var stopped = true

    private val bytesReadMap = HashMap<String, Long>()
    private val oldSpeedMap = HashMap<String, Long>()
    private val contentLengthMaps = HashMap<String, HashMap<Int, Long>>()
    private val receivedSizeMaps = HashMap<String, HashMap<Int, Long>>()

    fun start() {
        if (stopped) {
            stopped = false
            handler.post(this)
        }
    }

    fun stop() {
        if (!stopped) {
            stopped = true
            bytesReadMap.clear()
            oldSpeedMap.clear()
            contentLengthMaps.clear()
            receivedSizeMaps.clear()
            handler.removeCallbacks(this)
        }
    }

    fun onDownload(arcid: String, index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
        contentLengthMaps.getOrPut(arcid) { HashMap() }[index] = contentLength
        receivedSizeMaps.getOrPut(arcid) { HashMap() }[index] = receivedSize
        bytesReadMap[arcid] = (bytesReadMap[arcid] ?: 0L) + bytesRead
    }

    fun onDone(arcid: String, index: Int) {
        contentLengthMaps[arcid]?.remove(index)
        receivedSizeMaps[arcid]?.remove(index)
    }

    /**
     * Drops one archive's page maps; other in-flight tasks keep their
     * accounting. The byte/speed slices survive (legacy contract — cleared
     * by [stop] once every task is done): the scheduler removes the task
     * from the active list in the same dispatch, so they feed no tick.
     */
    fun onFinish(arcid: String) {
        contentLengthMaps.remove(arcid)
        receivedSizeMaps.remove(arcid)
    }

    /**
     * Byte-level fraction of this archive's in-flight pages:
     * Σ min(received/len, 1) over pages currently in the maps. Pages with
     * unknown content length (<=0) contribute 0. O(in-flight pages) — cheap
     * enough for page-event dispatch as well as the tick.
     */
    fun partialFor(arcid: String): Float {
        val contentLengths = contentLengthMaps[arcid] ?: return 0f
        val receivedSizes = receivedSizeMaps[arcid]
        var partial = 0f
        for ((index, contentLength) in contentLengths) {
            if (contentLength <= 0L) continue
            val received = receivedSizes?.get(index) ?: 0L
            partial += received.coerceIn(0L, contentLength).toFloat() / contentLength
        }
        return partial
    }

    override fun run() {
        for (info in callback.getActiveTasks()) {
            tick(info)
        }

        if (!stopped) {
            handler.postDelayed(this, 2000)
        }
    }

    private fun tick(info: DownloadInfo) {
        val arcid = info.arcid
        val snap = progressTracker.snapshot(arcid)
        val total = snap?.total ?: -1
        val downloaded = snap?.downloaded ?: 0

        var newSpeed = (bytesReadMap[arcid] ?: 0L) / 2
        val oldSpeed = oldSpeedMap[arcid]
        if (oldSpeed != null) {
            newSpeed = MathUtils.lerp(oldSpeed.toFloat(), newSpeed.toFloat(), 0.75f).toLong()
        }
        oldSpeedMap[arcid] = newSpeed
        bytesReadMap[arcid] = 0L

        // Remaining time; null keeps the prior value (legacy behaviour when
        // the maps yield downloadingCount==0, preserved post-W35-3c).
        val remaining: Long? = if (total <= 0) {
            -1L
        } else if (newSpeed == 0L) {
            300L * 24L * 60L * 60L * 1000L
        } else {
            val contentLengths = contentLengthMaps[arcid].orEmpty()
            val receivedSizes = receivedSizeMaps[arcid].orEmpty()
            var downloadingCount = 0
            var downloadingContentLengthSum = 0L
            var totalSize = 0L
            for ((index, contentLength) in contentLengths) {
                downloadingCount++
                downloadingContentLengthSum += contentLength
                totalSize += contentLength - (receivedSizes[index] ?: 0L)
            }
            if (downloadingCount != 0) {
                totalSize += downloadingContentLengthSum *
                    (total - downloaded - downloadingCount) / downloadingCount
                totalSize / newSpeed * 1000
            } else {
                null
            }
        }

        // One consolidated write per tick (was two): speed + remaining +
        // byte-level page fraction share a single StateFlow emission, so the
        // UI budget stays at one PAYLOAD_PROGRESS rebind per row per tick.
        progressTracker.update(
            arcid,
            speed = newSpeed,
            remaining = remaining,
            partialPages = partialFor(arcid)
        )

        callback.getDownloadListener()?.onDownload(info)
        val list = callback.getInfoListForLabel(info.label)
        if (list != null) {
            for (ref in callback.getDownloadInfoListeners()) {
                ref.get()?.onUpdate(info, list, callback.getWaitList())
            }
        }
    }
}
