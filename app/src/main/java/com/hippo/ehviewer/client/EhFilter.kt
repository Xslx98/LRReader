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

package com.hippo.ehviewer.client

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.Filter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class EhFilter @VisibleForTesting internal constructor() {

    private val mTitleFilterList = mutableListOf<Filter>()
    private val mUploaderFilterList = mutableListOf<Filter>()
    private val mTagFilterList = mutableListOf<Filter>()
    private val mTagNamespaceFilterList = mutableListOf<Filter>()

    /**
     * Completed when [loadFromDb] finishes (success OR failure path). All filter
     * consumers should call [awaitReady] before reading filter lists to avoid the
     * brief startup window where filters are empty (safe default = nothing filtered).
     *
     * Marked `@Volatile` so [loadFromList] can replace it after a synchronous
     * test load without racing other readers.
     */
    @Volatile
    private var readyDeferred: CompletableDeferred<Unit> = CompletableDeferred()

    val titleFilterList: List<Filter> get() = mTitleFilterList
    val uploaderFilterList: List<Filter> get() = mUploaderFilterList
    val tagFilterList: List<Filter> get() = mTagFilterList
    val tagNamespaceFilterList: List<Filter> get() = mTagNamespaceFilterList

    /**
     * Returns true once [loadFromDb] has finished (or [loadFromList] was used).
     * Useful for diagnostics; consumers should prefer [awaitReady].
     */
    val isReady: Boolean
        get() = readyDeferred.isCompleted

    /**
     * Load filters from database. Called once after construction from a coroutine.
     * Until this completes, filter lists are empty (nothing is filtered — safe default).
     *
     * Uses try/finally so [readyDeferred] completes on BOTH success and failure paths;
     * otherwise [awaitReady] callers would hang forever on a DB error.
     */
    internal suspend fun loadFromDb() {
        try {
            val list = EhDB.getAllFilterAsync()
            synchronized(this) {
                for (filter in list) {
                    distributeFilter(filter)
                }
            }
        } finally {
            readyDeferred.complete(Unit)
        }
    }

    /**
     * Suspend until filter loading has completed, or until [timeoutMs] elapses.
     *
     * Returns normally on success (filters loaded). On timeout, logs a warning,
     * reports the timeout to [Analytics], and **returns without throwing** —
     * filter lists are simply left in their current (likely empty) state and the
     * caller proceeds with the safe default of "nothing filtered". Timing out the
     * filter load must NOT break gallery list rendering; pass-through is the safe
     * behaviour.
     *
     * Idempotent: if loading is already complete this returns immediately without
     * suspending.
     */
    suspend fun awaitReady(timeoutMs: Long = DEFAULT_AWAIT_READY_TIMEOUT_MS) {
        if (readyDeferred.isCompleted) return
        val result = withTimeoutOrNull(timeoutMs) { readyDeferred.await() }
        if (result == null) {
            Log.w(TAG, "EhFilter.awaitReady() timed out after ${timeoutMs}ms — proceeding with empty filters")
            try {
                Analytics.recordException(
                    IllegalStateException("EhFilter.awaitReady timeout after ${timeoutMs}ms")
                )
            } catch (_: Throwable) {
                // Analytics is best-effort; never let reporting failures break the caller.
            }
        }
    }

    /**
     * Load filters from a pre-built list (for testing without DB).
     * Also completes [readyDeferred] so [awaitReady] returns immediately
     * after this call.
     */
    @Synchronized
    internal fun loadFromList(filters: List<Filter>) {
        for (filter in filters) {
            distributeFilter(filter)
        }
        readyDeferred.complete(Unit)
    }

    private fun distributeFilter(filter: Filter) {
        when (filter.mode) {
            MODE_TITLE -> {
                filter.text = filter.text?.lowercase()
                mTitleFilterList.add(filter)
            }
            MODE_UPLOADER -> {
                mUploaderFilterList.add(filter)
            }
            MODE_TAG -> {
                filter.text = filter.text?.lowercase()
                mTagFilterList.add(filter)
            }
            MODE_TAG_NAMESPACE -> {
                filter.text = filter.text?.lowercase()
                mTagNamespaceFilterList.add(filter)
            }
            else -> Log.d(TAG, "Unknown mode: ${filter.mode}")
        }
    }

    @Synchronized
    fun addFilter(filter: Filter) {
        filter.enable = true
        distributeFilter(filter)
        // Persist to DB in background
        ServiceRegistry.coroutineModule.ioScope.launch {
            EhDB.addFilterAsync(filter)
        }
    }

    @Synchronized
    fun triggerFilter(filter: Filter) {
        ServiceRegistry.coroutineModule.ioScope.launch {
            EhDB.triggerFilterAsync(filter)
        }
    }

    @Synchronized
    fun deleteFilter(filter: Filter) {
        when (filter.mode) {
            MODE_TITLE -> mTitleFilterList.remove(filter)
            MODE_UPLOADER -> mUploaderFilterList.remove(filter)
            MODE_TAG -> mTagFilterList.remove(filter)
            MODE_TAG_NAMESPACE -> mTagNamespaceFilterList.remove(filter)
            else -> Log.d(TAG, "Unknown mode: ${filter.mode}")
        }
        ServiceRegistry.coroutineModule.ioScope.launch {
            EhDB.deleteFilterAsync(filter)
        }
    }

    @Synchronized
    fun needTags(): Boolean =
        mTagFilterList.isNotEmpty() || mTagNamespaceFilterList.isNotEmpty()

    @Synchronized
    fun filterTitle(info: GalleryInfo?): Boolean {
        if (info == null) return false

        val title = info.title
        val filters = mTitleFilterList
        if (title != null && filters.isNotEmpty()) {
            for (filter in filters) {
                if (filter.enable == true && filter.text != null &&
                    title.lowercase().contains(filter.text!!)
                ) {
                    return false
                }
            }
        }
        return true
    }

    @Synchronized
    fun filterUploader(info: GalleryInfo?): Boolean {
        if (info == null) return false

        val uploader = info.uploader
        val filters = mUploaderFilterList
        if (uploader != null && filters.isNotEmpty()) {
            for (filter in filters) {
                if (filter.enable == true && uploader == filter.text) {
                    return false
                }
            }
        }
        return true
    }

    private fun matchTag(tag: String?, filter: String?): Boolean {
        if (tag == null || filter == null) return false

        val tagNamespace: String?
        val tagName: String
        val index = tag.indexOf(':')
        if (index < 0) {
            tagNamespace = null
            tagName = tag
        } else {
            tagNamespace = tag.substring(0, index)
            tagName = tag.substring(index + 1)
        }

        val filterNamespace: String?
        val filterName: String
        val filterIndex = filter.indexOf(':')
        if (filterIndex < 0) {
            filterNamespace = null
            filterName = filter
        } else {
            filterNamespace = filter.substring(0, filterIndex)
            filterName = filter.substring(filterIndex + 1)
        }

        if (tagNamespace != null && filterNamespace != null && tagNamespace != filterNamespace) {
            return false
        }
        return tagName == filterName
    }

    @Synchronized
    fun filterTag(info: GalleryInfo?): Boolean {
        if (info == null) return false

        val tags = info.simpleTags
        val filters = mTagFilterList
        if (tags != null && filters.isNotEmpty()) {
            for (tag in tags) {
                for (filter in filters) {
                    if (filter.enable == true && matchTag(tag, filter.text)) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun matchTagNamespace(tag: String?, filter: String?): Boolean {
        if (tag == null || filter == null) return false

        val index = tag.indexOf(':')
        if (index >= 0) {
            val tagNamespace = tag.substring(0, index)
            return tagNamespace == filter
        }
        return false
    }

    @Synchronized
    fun filterTagNamespace(info: GalleryInfo?): Boolean {
        if (info == null) return false

        val tags = info.simpleTags
        val filters = mTagNamespaceFilterList
        if (tags != null && filters.isNotEmpty()) {
            for (tag in tags) {
                for (filter in filters) {
                    if (filter.enable == true && matchTagNamespace(tag, filter.text)) {
                        return false
                    }
                }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "EhFilter"

        /** Default budget for [awaitReady] — DB load on cold start typically takes <100ms. */
        const val DEFAULT_AWAIT_READY_TIMEOUT_MS = 1000L

        const val MODE_TITLE = 0
        const val MODE_UPLOADER = 1
        const val MODE_TAG = 2
        const val MODE_TAG_NAMESPACE = 3

        @JvmStatic
        @Volatile
        private var sInstance: EhFilter? = null

        @JvmStatic
        fun getInstance(): EhFilter {
            if (sInstance == null) {
                sInstance = EhFilter()
            }
            return sInstance!!
        }
    }
}
