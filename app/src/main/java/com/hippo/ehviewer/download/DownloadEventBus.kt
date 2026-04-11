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
import java.lang.ref.WeakReference

/**
 * Manages download listener registration, iteration, and main-thread dispatch.
 *
 * Extracted from [DownloadManager] so that listener lifecycle and event routing
 * have a single, focused owner. All public methods must be called on the main
 * thread (enforced via [assertMainThread]).
 */
class DownloadEventBus {

    private val mainHandler = Handler(Looper.getMainLooper())

    // All listener collections are main-thread only.
    private var mDownloadListener: DownloadListener? = null
    @PublishedApi
    internal val mDownloadInfoListeners: MutableList<WeakReference<DownloadInfoListener>> = ArrayList()

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DownloadEventBus method must be called on the main thread, current: ${Thread.currentThread().name}"
        }
    }

    fun addDownloadInfoListener(listener: DownloadInfoListener) {
        assertMainThread()
        mDownloadInfoListeners.add(WeakReference(listener))
    }

    fun removeDownloadInfoListener(listener: DownloadInfoListener) {
        assertMainThread()
        mDownloadInfoListeners.removeAll { it.get() == null || it.get() === listener }
    }

    fun setDownloadListener(listener: DownloadListener?) {
        assertMainThread()
        mDownloadListener = listener
    }

    fun getDownloadListener(): DownloadListener? {
        return mDownloadListener
    }

    /**
     * Returns the raw list of weak references for [DownloadSpeedTracker.Callback].
     */
    fun getInfoListenerRefs(): List<WeakReference<DownloadInfoListener>> {
        return mDownloadInfoListeners
    }

    /**
     * Iterates over [mDownloadInfoListeners], unwrapping each [WeakReference]
     * and skipping GC'd entries. Periodically cleans up null refs.
     */
    inline fun forEachListener(action: (DownloadInfoListener) -> Unit) {
        var hasNull = false
        for (ref in mDownloadInfoListeners) {
            val listener = ref.get()
            if (listener != null) {
                action(listener)
            } else {
                hasNull = true
            }
        }
        if (hasNull) {
            mDownloadInfoListeners.removeAll { it.get() == null }
        }
    }

    /**
     * Posts [block] to the main thread handler for execution.
     */
    fun postToMain(block: () -> Unit) {
        mainHandler.post(block)
    }
}
