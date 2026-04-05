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

import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.lib.yorozuya.MathUtils
import com.hippo.lib.yorozuya.SimpleHandler
import com.hippo.lib.yorozuya.collect.SparseIJArray
import java.util.LinkedList

/**
 * Calculates and reports download speed and remaining time on a 2-second interval.
 * Extracted from DownloadManager to give it a single responsibility.
 *
 * Callbacks are supplied via [Callback] so this class does not need a
 * direct reference to DownloadManager.
 */
internal class DownloadSpeedTracker(private val callback: Callback) : Runnable {

    /** Supplies the data DownloadSpeedTracker needs from DownloadManager. */
    interface Callback {
        /** @return the first currently-active task, or null if idle. */
        fun getFirstActiveTask(): DownloadInfo?

        /** @return the per-label list used for listener notifications. */
        fun getInfoListForLabel(label: String?): List<DownloadInfo>?

        /** @return the active DownloadListener (may be null). */
        fun getDownloadListener(): DownloadListener?

        /** @return all registered DownloadInfoListeners. */
        fun getDownloadInfoListeners(): List<DownloadInfoListener>

        /** @return the current wait list (passed to onUpdate). */
        fun getWaitList(): LinkedList<DownloadInfo>
    }

    private var stopped = true
    private var bytesRead = 0L
    private var oldSpeed = -1L

    private val contentLengthMap = SparseIJArray()
    private val receivedSizeMap = SparseIJArray()

    fun start() {
        if (stopped) {
            stopped = false
            SimpleHandler.getInstance().post(this)
        }
    }

    fun stop() {
        if (!stopped) {
            stopped = true
            bytesRead = 0
            oldSpeed = -1
            contentLengthMap.clear()
            receivedSizeMap.clear()
            SimpleHandler.getInstance().removeCallbacks(this)
        }
    }

    fun onDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
        contentLengthMap.put(index, contentLength)
        receivedSizeMap.put(index, receivedSize)
        this.bytesRead += bytesRead
    }

    fun onDone(index: Int) {
        contentLengthMap.delete(index)
        receivedSizeMap.delete(index)
    }

    fun onFinish() {
        contentLengthMap.clear()
        receivedSizeMap.clear()
    }

    override fun run() {
        val info = callback.getFirstActiveTask()
        if (info != null) {
            var newSpeed = bytesRead / 2
            if (oldSpeed != -1L) {
                newSpeed = MathUtils.lerp(oldSpeed.toFloat(), newSpeed.toFloat(), 0.75f).toLong()
            }
            oldSpeed = newSpeed
            info.speed = newSpeed

            // Calculate remaining time
            if (info.total <= 0) {
                info.remaining = -1
            } else if (newSpeed == 0L) {
                info.remaining = 300L * 24L * 60L * 60L * 1000L // 300 days
            } else {
                var downloadingCount = 0
                var downloadingContentLengthSum = 0L
                var totalSize = 0L
                val n = maxOf(contentLengthMap.size(), receivedSizeMap.size())
                for (i in 0 until n) {
                    val contentLength = contentLengthMap.valueAt(i)
                    val receivedSize = receivedSizeMap.valueAt(i)
                    downloadingCount++
                    downloadingContentLengthSum += contentLength
                    totalSize += contentLength - receivedSize
                }
                if (downloadingCount != 0) {
                    totalSize += downloadingContentLengthSum *
                        (info.total - info.downloaded - downloadingCount) / downloadingCount
                    info.remaining = totalSize / newSpeed * 1000
                }
            }

            callback.getDownloadListener()?.onDownload(info)
            val list = callback.getInfoListForLabel(info.label)
            if (list != null) {
                for (l in callback.getDownloadInfoListeners()) {
                    l.onUpdate(info, list, callback.getWaitList())
                }
            }
        }

        bytesRead = 0

        if (!stopped) {
            SimpleHandler.getInstance().postDelayed(this, 2000)
        }
    }
}
