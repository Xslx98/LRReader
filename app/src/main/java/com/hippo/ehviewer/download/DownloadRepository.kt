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
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Owns download collection state and DB persistence.
 *
 * All in-memory collections are main-thread-only. Background DB writes are
 * dispatched via [scope]. The class provides query, mutation, and label CRUD
 * methods that [DownloadManager] (facade) and future DownloadScheduler consume.
 */
class DownloadRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ═══════════════════════════════════════════════════════════
    // Collections — internal so DownloadManager facade can access
    // ═══════════════════════════════════════════════════════════

    /** All download info, sorted by [DATE_DESC_COMPARATOR]. */
    internal val allInfoList: MutableList<DownloadInfo> = ArrayList()

    /** O(1) lookup by gid. */
    internal val allInfoMap: HashMap<Long, DownloadInfo> = HashMap()

    /** Label string → per-label info list. Does NOT contain default (null-label) entries. */
    internal val labelInfoMap: MutableMap<String?, MutableList<DownloadInfo>> = HashMap()

    /** Label string → count cache. */
    internal val labelCountMap: MutableMap<String?, Long> = HashMap()

    /** All labels (without the implicit default label). */
    internal val labelList: MutableList<DownloadLabel> = mutableListOf()

    /** O(1) label existence check. */
    internal val labelSet: HashSet<String> = HashSet()

    /** Infos with null (default) label. */
    internal val defaultInfoList: MutableList<DownloadInfo> = ArrayList()

    // ═══════════════════════════════════════════════════════════
    // Init lifecycle
    // ═══════════════════════════════════════════════════════════

    /** Signals when async init is complete. */
    internal val initDeferred = CompletableDeferred<Unit>()

    @Volatile
    internal var initialized = false

    // ═══════════════════════════════════════════════════════════
    // Thread helpers
    // ═══════════════════════════════════════════════════════════

    internal fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DownloadRepository method must be called on the main thread, current: ${Thread.currentThread().name}"
        }
    }

    /**
     * Run [block] on the main (UI) thread. If the caller is already on the main
     * thread, the block executes inline; otherwise it is posted to the main looper.
     */
    internal fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Loading
    // ═══════════════════════════════════════════════════════════

    /**
     * Holds the result of [loadDataFromDb]'s IO phase before it is published to
     * main-thread-only collections.
     */
    internal data class LoadedDownloadData(
        val labels: List<DownloadLabel>,
        val extraSavedLabels: List<DownloadLabel>,
        val labelStrings: Set<String>,
        val allInfoList: List<DownloadInfo>,
        val labelToInfoList: Map<String?, MutableList<DownloadInfo>>
    )

    /**
     * Kick off the async DB load. On completion, [onComplete] is invoked on the
     * main thread (after collections are populated).
     */
    fun startLoading(onComplete: () -> Unit) {
        scope.launch {
            try {
                loadDataFromDb(onComplete)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load download data from DB", e)
                runOnMainThread {
                    initialized = true
                    initDeferred.complete(Unit)
                    onComplete()
                }
            }
        }
    }

    /**
     * Load labels and download info from the database.
     *
     * Runs on a background thread. The IO phase reads the DB and constructs the
     * results in local variables only. The results are then handed to the main
     * thread via [runOnMainThread], where the shared collections are populated.
     */
    private suspend fun loadDataFromDb(onComplete: () -> Unit) {
        // ── IO phase ───────────────────────────────────────────────────
        val loadedLabels = EhDB.getAllDownloadLabelListAsync()
        val loadedInfos = EhDB.getAllDownloadInfoAsync()

        val labelStrings: HashSet<String> = HashSet()
        for (label in loadedLabels) {
            label.label?.let { labelStrings.add(it) }
        }

        val mapDraft: LinkedHashMap<String?, MutableList<DownloadInfo>> = LinkedHashMap()
        for (label in loadedLabels) {
            mapDraft[label.label] = ArrayList()
        }

        val orphanLabelStrings: MutableList<String> = ArrayList()

        for (info in loadedInfos) {
            val archiveUri = info.archiveUri
            if (archiveUri != null && archiveUri.startsWith("content://")) {
                try {
                    val uri = Uri.parse(archiveUri)
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore URI permission for $archiveUri", e)
                }
            }

            var list = mapDraft[info.label]
            if (list == null) {
                list = ArrayList()
                mapDraft[info.label] = list
                val infoLabel = info.label
                if (infoLabel != null && !labelStrings.contains(infoLabel)) {
                    orphanLabelStrings.add(infoLabel)
                    labelStrings.add(infoLabel)
                }
            }
            list.add(info)
        }

        val extraSavedLabels = EhDB.addDownloadLabelsAsync(orphanLabelStrings)

        val loaded = LoadedDownloadData(
            labels = loadedLabels,
            extraSavedLabels = extraSavedLabels,
            labelStrings = labelStrings,
            allInfoList = loadedInfos,
            labelToInfoList = mapDraft
        )

        // ── Main-thread publish phase ──────────────────────────────────
        runOnMainThread {
            publishLoadedData(loaded)
            onComplete()
        }
    }

    /**
     * Publish loaded DB data into the main-thread-only collections.
     * Must be called on the main thread.
     */
    internal fun publishLoadedData(loaded: LoadedDownloadData) {
        labelList.addAll(loaded.labels)
        labelList.addAll(loaded.extraSavedLabels)
        labelSet.addAll(loaded.labelStrings)

        allInfoList.addAll(loaded.allInfoList)
        for (info in loaded.allInfoList) {
            allInfoMap[info.gid] = info
        }

        for ((label, list) in loaded.labelToInfoList) {
            labelInfoMap[label] = list
        }

        for ((key, value) in labelInfoMap) {
            labelCountMap[key] = value.size.toLong()
        }

        initialized = true
        initDeferred.complete(Unit)
    }

    // ═══════════════════════════════════════════════════════════
    // Query methods
    // ═══════════════════════════════════════════════════════════

    /**
     * Returns the info list for the given label. Null label returns [defaultInfoList].
     * Returns null if the label does not exist in [labelInfoMap].
     */
    fun getInfoListForLabel(label: String?): MutableList<DownloadInfo>? {
        return if (label == null) {
            defaultInfoList
        } else {
            labelInfoMap[label]
        }
    }

    fun containLabel(label: String?): Boolean {
        assertMainThread()
        if (label == null) return false
        return labelSet.contains(label)
    }

    fun containDownloadInfo(gid: Long): Boolean {
        assertMainThread()
        return allInfoMap.containsKey(gid)
    }

    fun getDownloadInfo(gid: Long): DownloadInfo? {
        assertMainThread()
        return allInfoMap[gid]
    }

    fun getDownloadState(gid: Long): Int {
        assertMainThread()
        val info = allInfoMap[gid]
        return info?.state ?: DownloadInfo.STATE_INVALID
    }

    fun getLabelCount(label: String?): Long {
        assertMainThread()
        return try {
            labelCountMap[label] ?: 0L
        } catch (e: NullPointerException) {
            Analytics.recordException(e)
            0L
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Info mutations
    // ═══════════════════════════════════════════════════════════

    /**
     * Add [info] to all collections (allInfoList, allInfoMap, per-label list).
     * Inserts at position 0 (newest-first) without sorting.
     */
    fun addInfo(info: DownloadInfo) {
        assertMainThread()
        allInfoList.add(0, info)
        allInfoMap[info.gid] = info
        val list = getInfoListForLabel(info.label)
        list?.add(0, info)
    }

    /**
     * Add [info] to all collections using binary-insertion to maintain
     * [DATE_DESC_COMPARATOR] order in allInfoList and the per-label list.
     */
    fun addInfoSorted(info: DownloadInfo) {
        assertMainThread()
        insertSorted(allInfoList, info)
        allInfoMap[info.gid] = info
        val list = getInfoListForLabel(info.label)
        if (list != null) {
            insertSorted(list, info)
        }
    }

    /**
     * Remove [info] from all collections.
     * Returns the index in the per-label list where it was found, or -1.
     */
    fun removeInfo(info: DownloadInfo): Int {
        assertMainThread()
        allInfoList.remove(info)
        allInfoMap.remove(info.gid)
        val list = getInfoListForLabel(info.label)
        if (list != null) {
            val index = list.indexOf(info)
            if (index >= 0) {
                list.removeAt(index)
                return index
            }
        }
        return -1
    }

    /**
     * Batch-remove infos by gid set. More efficient than N individual [removeInfo] calls.
     */
    fun removeInfoBatch(gidSet: Set<Long>) {
        assertMainThread()
        for (gid in gidSet) {
            val info = allInfoMap.remove(gid)
            if (info != null) {
                val list = getInfoListForLabel(info.label)
                list?.remove(info)
            }
        }
        allInfoList.removeAll { it.gid in gidSet }
    }

    /**
     * Replace [oldInfo] with [newInfo] in all collections.
     */
    fun replaceInfo(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
        assertMainThread()
        for (i in allInfoList.indices) {
            if (oldInfo.gid == allInfoList[i].gid) {
                allInfoList[i] = newInfo
                break
            }
        }
        val infoList = getInfoListForLabel(oldInfo.label)
        if (infoList != null) {
            for (i in infoList.indices) {
                if (oldInfo.gid == infoList[i].gid) {
                    infoList[i] = newInfo
                    break
                }
            }
        }
        allInfoMap.remove(oldInfo.gid)
        allInfoMap[newInfo.gid] = newInfo
    }

    // ═══════════════════════════════════════════════════════════
    // Batch import helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Import a batch of [DownloadInfo] objects (e.g., from file import).
     * Inserts into collections in sorted order, creates missing per-label
     * lists, and persists to DB. Returns the set of new label strings that
     * need to be persisted.
     */
    fun importInfoBatch(infos: List<DownloadInfo>): List<String> {
        assertMainThread()
        val newLabels = mutableListOf<String>()
        for (info in infos) {
            if (containDownloadInfo(info.gid)) continue
            if (DownloadInfo.STATE_WAIT == info.state || DownloadInfo.STATE_DOWNLOAD == info.state) {
                info.state = DownloadInfo.STATE_NONE
            }
            var list = getInfoListForLabel(info.label)
            if (list == null) {
                list = ArrayList()
                labelInfoMap[info.label] = list
                if (!containLabel(info.label) && info.label != null) {
                    newLabels.add(info.label!!)
                }
            }
            insertSorted(list, info)
            insertSorted(allInfoList, info)
            allInfoMap[info.gid] = info
        }
        return newLabels
    }

    /**
     * Import a batch of [DownloadLabel] objects. Skips duplicates.
     * Returns the labels that need to be persisted (not yet in labelSet).
     */
    fun importLabelBatch(labels: List<DownloadLabel>): List<DownloadLabel> {
        assertMainThread()
        val toAdd = mutableListOf<DownloadLabel>()
        for (label in labels) {
            val s = label.label
            if (!containLabel(s)) {
                labelInfoMap[s] = ArrayList()
                toAdd.add(label)
            }
        }
        return toAdd
    }

    /**
     * Add a single download (from UI). Returns the per-label list the info
     * was added to, or null if the label list was not found.
     */
    fun addSingleDownload(galleryInfo: GalleryInfo, label: String?, state: Int): Pair<DownloadInfo, MutableList<DownloadInfo>>? {
        assertMainThread()
        if (containDownloadInfo(galleryInfo.gid)) return null

        val info = DownloadInfo(galleryInfo)
        info.label = label
        info.state = state
        info.time = System.currentTimeMillis()

        val list = getInfoListForLabel(info.label)
        if (!labelCountMap.containsKey(label)) {
            labelCountMap[label] = 1L
        } else {
            labelCountMap[label] = (labelCountMap[label] ?: 0L) + 1L
        }
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: $label")
            return null
        }
        list.add(0, info)
        allInfoList.add(0, info)
        allInfoMap[galleryInfo.gid] = info
        persistInfo(info)
        return Pair(info, list)
    }

    /**
     * Add download info without notifying (for sync/import). Returns true if added.
     */
    fun addInfoOnly(galleryInfo: GalleryInfo, label: String?): Boolean {
        assertMainThread()
        if (containDownloadInfo(galleryInfo.gid)) return false
        val info = DownloadInfo(galleryInfo)
        info.label = label
        info.state = DownloadInfo.STATE_NONE
        if (info.time == 0L) info.time = System.currentTimeMillis()
        val list = getInfoListForLabel(info.label) ?: run {
            Log.e(TAG, "Can't find download info list with label: $label")
            return false
        }
        list.add(0, info)
        persistInfo(info)
        allInfoMap[galleryInfo.gid] = info
        return true
    }

    /**
     * Remove download info from collections and DB by gid.
     * Returns (info, label-list, index-in-label-list) or null.
     */
    fun deleteInfo(gid: Long): Triple<DownloadInfo, MutableList<DownloadInfo>, Int>? {
        assertMainThread()
        val info = allInfoMap[gid] ?: return null
        allInfoList.remove(info)
        allInfoMap.remove(info.gid)
        val list = getInfoListForLabel(info.label)
        val index = if (list != null) {
            val idx = list.indexOf(info)
            if (idx >= 0) list.removeAt(idx)
            idx
        } else -1
        removeInfoFromDb(info.gid)
        return Triple(info, list ?: ArrayList(), index)
    }

    /**
     * Remove a range of download infos from collections and DB.
     */
    fun deleteInfoRange(gidSet: Set<Long>) {
        assertMainThread()
        val gidsToRemove = mutableListOf<Long>()
        for (gid in gidSet) {
            val info = allInfoMap.remove(gid)
            if (info != null) {
                gidsToRemove.add(info.gid)
                getInfoListForLabel(info.label)?.remove(info)
            }
        }
        allInfoList.removeAll { it.gid in gidSet }
        if (gidsToRemove.isNotEmpty()) removeInfoBatchFromDb(gidsToRemove)
    }

    // ═══════════════════════════════════════════════════════════
    // DB persistence helpers
    // ═══════════════════════════════════════════════════════════

    /** Persist [info] to the downloads table on a background thread. */
    fun persistInfo(info: DownloadInfo) {
        scope.launch { EhDB.putDownloadInfoAsync(info) }
    }

    /** Persist [info] to the history table on a background thread. */
    fun persistHistory(info: DownloadInfo) {
        scope.launch { EhDB.putHistoryInfoAsync(info) }
    }

    /** Remove download info from the DB by gid on a background thread. */
    fun removeInfoFromDb(gid: Long) {
        scope.launch { EhDB.removeDownloadInfoAsync(gid) }
    }

    /** Batch-remove download infos from the DB on a background thread. */
    fun removeInfoBatchFromDb(gids: List<Long>) {
        scope.launch { EhDB.removeDownloadInfoBatchAsync(gids) }
    }

    /** Batch-persist download infos on a background thread. */
    fun persistInfoBatch(list: List<DownloadInfo>) {
        scope.launch { EhDB.putDownloadInfoBatchAsync(list) }
    }

    // ═══════════════════════════════════════════════════════════
    // Reload
    // ═══════════════════════════════════════════════════════════

    /**
     * Clear all in-memory collections and reload from DB.
     * [onComplete] is called on the main thread after reload finishes.
     */
    fun reload(onComplete: () -> Unit) {
        assertMainThread()

        allInfoList.clear()
        allInfoMap.clear()
        defaultInfoList.clear()
        for ((_, value) in labelInfoMap) {
            value.clear()
        }

        scope.launch {
            val reloadedInfos = EhDB.getAllDownloadInfoAsync()
            runOnMainThread {
                allInfoList.addAll(reloadedInfos)
                for (info in reloadedInfos) {
                    allInfoMap[info.gid] = info
                    var list = getInfoListForLabel(info.label)
                    if (list == null) {
                        list = ArrayList()
                        labelInfoMap[info.label] = list
                    }
                    list.add(info)
                }
                onComplete()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Label CRUD
    // ═══════════════════════════════════════════════════════════

    /**
     * Add a new label. Returns true if the label was added, false if it already
     * exists or is null.
     */
    fun addLabel(label: String?): Boolean {
        assertMainThread()
        if (label == null || containLabel(label)) {
            return false
        }

        val newLabel = DownloadLabel().apply {
            this.label = label
            this.time = System.currentTimeMillis()
        }
        labelList.add(newLabel)
        labelSet.add(label)
        labelInfoMap[label] = ArrayList()

        scope.launch {
            val saved = EhDB.addDownloadLabelAsync(label)
            runOnMainThread {
                newLabel.id = saved.id
                newLabel.time = saved.time
            }
        }

        return true
    }

    fun moveLabel(fromPosition: Int, toPosition: Int) {
        assertMainThread()
        val item = labelList.removeAt(fromPosition)
        labelList.add(toPosition, item)
        scope.launch { EhDB.moveDownloadLabelAsync(fromPosition, toPosition) }
    }

    /**
     * Rename a label. Returns the list of affected [DownloadInfo] whose labels
     * were updated, or null if the label was not found.
     */
    fun renameLabel(from: String, to: String): List<DownloadInfo>? {
        assertMainThread()
        var rawLabel: DownloadLabel? = null
        for (raw in labelList) {
            if (from == raw.label) {
                rawLabel = raw
                raw.label = to
                break
            }
        }
        if (rawLabel == null) return null

        labelSet.remove(from)
        labelSet.add(to)

        val list = labelInfoMap.remove(from) ?: return null

        for (info in list) {
            info.label = to
        }
        labelInfoMap[to] = list

        val labelToUpdate = rawLabel
        val infosToUpdate = ArrayList(list)
        scope.launch {
            EhDB.updateDownloadLabelAsync(labelToUpdate)
            EhDB.putDownloadInfoBatchAsync(infosToUpdate)
        }

        return list
    }

    /**
     * Delete a label. Moves all infos with that label to the default (null) list
     * using sorted insertion. Returns the list of affected [DownloadInfo], or null
     * if the label was not found.
     */
    fun deleteLabel(label: String): List<DownloadInfo>? {
        assertMainThread()
        var removedLabel: DownloadLabel? = null
        val iterator = labelList.iterator()
        while (iterator.hasNext()) {
            val raw = iterator.next()
            if (label == raw.label) {
                removedLabel = raw
                iterator.remove()
                break
            }
        }
        if (removedLabel == null) return null

        labelSet.remove(label)

        val list = labelInfoMap.remove(label) ?: return null

        for (info in list) {
            info.label = null
            insertSorted(defaultInfoList, info)
        }

        val labelToRemove = removedLabel
        val infosToUpdate = ArrayList(list)
        scope.launch {
            EhDB.removeDownloadLabelAsync(labelToRemove)
            EhDB.putDownloadInfoBatchAsync(infosToUpdate)
        }

        return list
    }

    /**
     * Move [infos] to the given [label]. The label must already exist (or be null
     * for default). Removes infos from their current per-label lists and inserts
     * them into the destination list in sorted order.
     */
    fun changeLabel(infos: List<DownloadInfo>, label: String?) {
        assertMainThread()
        if (label != null && !containLabel(label)) {
            Log.e(TAG, "Label does not exist: $label")
            return
        }

        val dstList = getInfoListForLabel(label)
        if (dstList == null) {
            Log.e(TAG, "Can't find label list for: $label")
            return
        }

        for (info in infos) {
            if (info.label == label) continue

            val srcList = getInfoListForLabel(info.label)
            if (srcList == null) {
                Log.e(TAG, "Can't find label list for: ${info.label}")
                continue
            }

            srcList.remove(info)
            info.label = label
            insertSorted(dstList, info)

            scope.launch { EhDB.putDownloadInfoAsync(info) }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Static utilities
    // ═══════════════════════════════════════════════════════════

    companion object {
        private val TAG = DownloadRepository::class.java.simpleName

        @JvmField
        val DATE_DESC_COMPARATOR: Comparator<DownloadInfo> = Comparator { lhs, rhs ->
            val dif = lhs.time - rhs.time
            when {
                dif > 0 -> -1
                dif < 0 -> 1
                else -> 0
            }
        }

        /**
         * Insert [item] into a list that is already sorted by [DATE_DESC_COMPARATOR].
         * Uses binary search to find the correct insertion point in O(log N),
         * avoiding a full O(N log N) re-sort.
         */
        internal fun insertSorted(list: MutableList<DownloadInfo>, item: DownloadInfo) {
            val index = Collections.binarySearch(list, item, DATE_DESC_COMPARATOR)
            val insertionPoint = if (index < 0) -(index + 1) else index
            list.add(insertionPoint, item)
        }
    }
}
