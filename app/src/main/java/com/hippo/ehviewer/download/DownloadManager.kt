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
import android.os.Looper
import android.util.Log
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.lib.image.Image
import com.hippo.lib.yorozuya.ObjectUtils
import com.hippo.lib.yorozuya.collect.LongList
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Collections

class DownloadManager(
    private val mContext: Context,
    private val scope: CoroutineScope = ServiceRegistry.coroutineModule.ioScope,
    internal val eventBus: DownloadEventBus = DownloadEventBus()
) {

    // All download info list
    private val mAllInfoList: MutableList<DownloadInfo> = ArrayList()
    // All download info map — O(1) lookup by gid
    private val mAllInfoMap: HashMap<Long, DownloadInfo> = HashMap()
    // label and info list map, without default label info list
    private val mMap: MutableMap<String?, MutableList<DownloadInfo>> = HashMap()
    private val mLabelCountMap: MutableMap<String?, Long> = HashMap()
    // All labels without default label
    private val mLabelList: MutableList<DownloadLabel> = mutableListOf()
    // O(1) label existence check
    private val mLabelSet: HashSet<String> = HashSet()
    // Store download info with default label
    private val mDefaultInfoList: MutableList<DownloadInfo> = ArrayList()
    // Store download info wait to start
    private val mWaitList: MutableList<DownloadInfo> = ArrayList()

    private val mSpeedReminder: DownloadSpeedTracker

    private val mActiveTasks: MutableList<DownloadInfo> = ArrayList()
    private val mActiveWorkers: MutableMap<DownloadInfo, LRRDownloadWorker> = HashMap()

    /** Signals when async init is complete. */
    private val mInitDeferred = CompletableDeferred<Unit>()

    @Volatile
    private var mInitialized = false

    /**
     * Assert that the current thread is the main (UI) thread.
     * All public read/write methods on shared collections must be called from main thread.
     */
    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DownloadManager method must be called on the main thread, current: ${Thread.currentThread().name}"
        }
    }

    /**
     * Run [block] on the main (UI) thread. If the caller is already on the main
     * thread, the block executes inline; otherwise it is posted to the main
     * looper. Used to publish IO-phase results back into the main-thread-only
     * collections without deadlocking tests that drive coroutines on
     * [kotlinx.coroutines.Dispatchers.Unconfined].
     */
    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            eventBus.postToMain(block)
        }
    }

    init {
        mSpeedReminder = DownloadSpeedTracker(object : DownloadSpeedTracker.Callback {
            override fun getFirstActiveTask(): DownloadInfo? {
                return if (mActiveTasks.isEmpty()) null else mActiveTasks[0]
            }

            override fun getInfoListForLabel(label: String?): List<DownloadInfo>? {
                return this@DownloadManager.getInfoListForLabel(label)
            }

            override fun getDownloadListener(): DownloadListener? {
                return eventBus.getDownloadListener()
            }

            override fun getDownloadInfoListeners(): List<WeakReference<DownloadInfoListener>> {
                return eventBus.getInfoListenerRefs()
            }

            override fun getWaitList(): List<DownloadInfo> {
                return mWaitList
            }
        })

        // Load data from DB on a background thread to avoid blocking main thread.
        // The IO segment only reads/writes the DB; the resulting data is published to
        // the main-thread-only collections via eventBus.postToMain so that no shared
        // mutable state is touched off the main thread.
        scope.launch {
            try {
                loadDataFromDb()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load download data from DB", e)
                // Even on failure we must release waiters; publish an empty result.
                runOnMainThread {
                    mInitialized = true
                    mInitDeferred.complete(Unit)
                }
            }
        }
    }

    /**
     * Holds the result of [loadDataFromDb]'s IO phase before it is published to
     * main-thread-only collections.
     */
    private data class LoadedDownloadData(
        val labels: List<DownloadLabel>,
        val extraSavedLabels: List<DownloadLabel>,
        val labelStrings: Set<String>,
        val allInfoList: List<DownloadInfo>,
        val labelToInfoList: Map<String?, MutableList<DownloadInfo>>
    )

    /**
     * Load labels and download info from the database.
     *
     * Runs on a background thread. The IO phase reads the DB and constructs the
     * results in local variables only. The results are then handed to the main
     * thread via [eventBus.postToMain], where the shared collections (which are
     * declared main-thread-only) are populated atomically.
     */
    private suspend fun loadDataFromDb() {
        // ── IO phase ───────────────────────────────────────────────────
        val loadedLabels = EhDB.getAllDownloadLabelListAsync()
        val loadedInfos = EhDB.getAllDownloadInfoAsync()

        // Build a draft of the per-label info lists in local memory only.
        val labelStrings: HashSet<String> = HashSet()
        for (label in loadedLabels) {
            label.label?.let { labelStrings.add(it) }
        }

        // mMap uses label-string (nullable) as the key. Preserve insertion order
        // so the published main-thread state matches what the old code produced.
        val mapDraft: LinkedHashMap<String?, MutableList<DownloadInfo>> = LinkedHashMap()
        for (label in loadedLabels) {
            mapDraft[label.label] = ArrayList()
        }

        // Collect orphan label strings referenced by downloads but absent from
        // the label table. These will be batch-inserted in a single DB transaction
        // after the loop, instead of N individual addDownloadLabelAsync() calls.
        val orphanLabelStrings: MutableList<String> = ArrayList()

        for (info in loadedInfos) {
            val archiveUri = info.archiveUri
            if (archiveUri != null && archiveUri.startsWith("content://")) {
                try {
                    val uri = Uri.parse(archiveUri)
                    mContext.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w("DownloadManager", "Failed to restore URI permission for $archiveUri", e)
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

        // Batch-insert all orphan labels in a single DB transaction — O(1) roundtrip
        // instead of O(N) individual inserts.
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
        }
    }

    /**
     * Publish loaded DB data into the main-thread-only collections. Must be
     * called on the main thread.
     */
    private fun publishLoadedData(loaded: LoadedDownloadData) {
        mLabelList.addAll(loaded.labels)
        mLabelList.addAll(loaded.extraSavedLabels)
        mLabelSet.addAll(loaded.labelStrings)

        mAllInfoList.addAll(loaded.allInfoList)
        for (info in loaded.allInfoList) {
            mAllInfoMap[info.gid] = info
        }

        for ((label, list) in loaded.labelToInfoList) {
            mMap[label] = list
        }

        for ((key, value) in mMap) {
            mLabelCountMap[key] = value.size.toLong()
        }

        // Mark initialization complete *after* the shared state is fully published
        // so that any caller awaiting init sees a consistent snapshot.
        mInitialized = true
        mInitDeferred.complete(Unit)

        eventBus.forEachListener { it.onReload() }
    }

    /**
     * Wait for async initialization to complete. Call this from a coroutine
     * launched on a background dispatcher (e.g., [DownloadService] launches
     * this from its service-scoped IO coroutine after [android.app.Service.startForeground]
     * has already been invoked with a placeholder notification, so the 5-second
     * foreground-service ANR window is satisfied before we suspend here).
     *
     * No-op if already initialized. Guarded against main-thread invocation to
     * preserve the historical invariant — even though `suspend` means this
     * function would not strictly block the UI thread, suspending the main
     * dispatcher while init is in flight would still prevent the IO→main
     * publish post (see [publishLoadedData]) from running, deadlocking the
     * caller. The guard makes misuse fail fast.
     *
     * Times out after [timeoutMs] (default 10 seconds) to prevent permanent
     * waiting if the IO phase wedges.
     */
    suspend fun awaitInitAsync(timeoutMs: Long = 10_000L) {
        if (mInitialized) return
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "awaitInitAsync() must not be called on the main thread"
        }
        kotlinx.coroutines.withTimeout(timeoutMs) { mInitDeferred.await() }
    }

    fun replaceInfo(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
        assertMainThread()
        for (i in mAllInfoList.indices) {
            if (oldInfo.gid == mAllInfoList[i].gid) {
                mAllInfoList[i] = newInfo
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

        mAllInfoMap.remove(oldInfo.gid)
        mAllInfoMap[newInfo.gid] = newInfo

        eventBus.forEachListener { it.onReplace(newInfo, oldInfo) }
    }

    private fun getInfoListForLabel(label: String?): MutableList<DownloadInfo>? {
        return if (label == null) {
            mDefaultInfoList
        } else {
            mMap[label]
        }
    }

    fun containLabel(label: String?): Boolean {
        assertMainThread()
        if (label == null) {
            return false
        }
        return mLabelSet.contains(label)
    }

    fun containDownloadInfo(gid: Long): Boolean {
        assertMainThread()
        return mAllInfoMap.containsKey(gid)
    }

    val labelList: List<DownloadLabel>
        get() {
            assertMainThread()
            return mLabelList
        }

    fun getLabelCount(label: String?): Long {
        assertMainThread()
        return try {
            mLabelCountMap[label] ?: 0L
        } catch (e: NullPointerException) {
            Analytics.recordException(e)
            0L
        }
    }

    val allDownloadInfoList: List<DownloadInfo>
        get() {
            assertMainThread()
            return mAllInfoList
        }

    /**
     * Reload download data from DB for the current server profile.
     * Call this after switching servers. Must be called on the main thread.
     */
    fun reload() {
        assertMainThread()

        // Stop any current downloads
        stopAllDownload()

        // Clear in-memory lists
        mAllInfoList.clear()
        mAllInfoMap.clear()
        mDefaultInfoList.clear()
        for ((_, value) in mMap) {
            value.clear()
        }

        // Load from DB on background, then post results back to main thread
        scope.launch {
            val allInfoList = EhDB.getAllDownloadInfoAsync()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                mAllInfoList.addAll(allInfoList)
                for (info in allInfoList) {
                    mAllInfoMap[info.gid] = info
                    var list = getInfoListForLabel(info.label)
                    if (list == null) {
                        list = ArrayList()
                        mMap[info.label] = list
                    }
                    list.add(info)
                }
                eventBus.forEachListener {
                    it.onReload()
                }
            }
        }
    }

    val defaultDownloadInfoList: List<DownloadInfo>
        get() {
            assertMainThread()
            return mDefaultInfoList
        }

    fun getLabelDownloadInfoList(label: String?): List<DownloadInfo>? {
        assertMainThread()
        return mMap[label]
    }

    val downloadInfoList: List<GalleryInfo>
        get() {
            assertMainThread()
            return ArrayList(mAllInfoList)
        }

    fun getDownloadInfo(gid: Long): DownloadInfo? {
        assertMainThread()
        return mAllInfoMap[gid]
    }

    fun getNoneDownloadInfo(gid: Long): DownloadInfo? {
        assertMainThread()
        var wasActive = false
        for (info in mActiveTasks) {
            if (info.gid == gid) {
                wasActive = true
                break
            }
        }
        if (wasActive) {
            stopDownloadInternal(gid)
        } else {
            val iterator = mWaitList.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                if (info.gid == gid) {
                    info.state = DownloadInfo.STATE_NONE
                    iterator.remove()
                    break
                }
            }
        }
        return mAllInfoMap[gid]
    }

    fun getDownloadState(gid: Long): Int {
        assertMainThread()
        val info = mAllInfoMap[gid]
        return info?.state ?: DownloadInfo.STATE_INVALID
    }

    fun addDownloadInfoListener(downloadInfoListener: DownloadInfoListener?) {
        eventBus.addDownloadInfoListener(downloadInfoListener!!)
    }

    fun removeDownloadInfoListener(downloadInfoListener: DownloadInfoListener?) {
        eventBus.removeDownloadInfoListener(downloadInfoListener!!)
    }

    fun setDownloadListener(listener: DownloadListener?) {
        eventBus.setDownloadListener(listener)
    }

    private fun ensureDownload() {
        val maxConcurrent = DownloadSettings.getConcurrentDownloads()
        while (mActiveTasks.size < maxConcurrent && mWaitList.isNotEmpty()) {
            val info = mWaitList.removeAt(0)
            val worker = LRRDownloadWorker(mContext, info)
            mActiveTasks.add(info)
            mActiveWorkers[info] = worker
            worker.listener = PerTaskListener(info)
            info.state = DownloadInfo.STATE_DOWNLOAD
            info.speed = -1
            info.remaining = -1
            info.total = -1
            info.finished = 0
            info.downloaded = 0
            info.legacy = -1
            // Update in DB
            scope.launch { EhDB.putDownloadInfoAsync(info) }
            // Start speed count
            mSpeedReminder.start()
            // Notify start downloading
            eventBus.getDownloadListener()?.onStart(info)
            // Notify state update
            val list = getInfoListForLabel(info.label)
            if (list != null) {
                eventBus.forEachListener { it.onUpdate(info, list, mWaitList) }
            }
            // Start the worker
            worker.start()
        }
    }

    fun startDownload(galleryInfo: GalleryInfo, label: String?) {
        assertMainThread()
        for (active in mActiveTasks) {
            if (active.gid == galleryInfo.gid) return // already downloading
        }

        // Do nothing in the case of a local compressed file.
        if (galleryInfo is DownloadInfo) {
            val uri = galleryInfo.archiveUri
            if (uri != null && uri.startsWith("content://")) {
                return
            }
        }

        // Check in download list
        val existing = mAllInfoMap[galleryInfo.gid]

        if (existing != null) { // Get it in download list
            if (existing.state != DownloadInfo.STATE_WAIT) {
                // Set state DownloadInfo.STATE_WAIT
                existing.state = DownloadInfo.STATE_WAIT
                // Add to wait list
                mWaitList.add(existing)
                // Update in DB
                scope.launch { EhDB.putDownloadInfoAsync(existing) }
                // Notify state update
                val list = getInfoListForLabel(existing.label)
                if (list != null) {
                    eventBus.forEachListener {
                        it.onUpdate(existing, list, mWaitList)
                    }
                }
                // Make sure download is running
                ensureDownload()
            }
        } else {
            // It is new download info
            val info = DownloadInfo(galleryInfo)
            info.label = label
            info.state = DownloadInfo.STATE_WAIT
            info.time = System.currentTimeMillis()

            // Add to label download list
            val list = getInfoListForLabel(info.label)
            if (list == null) {
                Log.e(TAG, "Can't find download info list with label: $label")
                return
            }
            list.add(0, info)

            // Add to all download list and map
            mAllInfoList.add(0, info)
            mAllInfoMap[galleryInfo.gid] = info

            // Add to wait list
            mWaitList.add(info)

            // Save to DB
            scope.launch { EhDB.putDownloadInfoAsync(info) }

            // Notify
            eventBus.forEachListener { it.onAdd(info, list, list.size - 1) }
            // Make sure download is running
            ensureDownload()

            // Add it to history
            scope.launch { EhDB.putHistoryInfoAsync(info) }
        }
    }

    fun startRangeDownload(gidList: LongList) {
        assertMainThread()
        var update = false
        val downloadOrder = DownloadSettings.getDownloadOrder()
        if (downloadOrder) {
            for (i in 0 until gidList.size()) {
                val gid = gidList.get(i)
                val info = mAllInfoMap[gid]
                if (info == null) {
                    Log.d(TAG, "Can't get download info with gid: $gid")
                    continue
                }

                if (info.state == DownloadInfo.STATE_NONE ||
                    info.state == DownloadInfo.STATE_FAILED ||
                    info.state == DownloadInfo.STATE_FINISH
                ) {
                    update = true
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT
                    // Add to wait list
                    mWaitList.add(info)
                    // Update in DB
                    scope.launch { EhDB.putDownloadInfoAsync(info) }
                }
            }
        } else {
            var i = gidList.size()
            while (i > 0) {
                val gid = gidList.get(i - 1)
                val info = mAllInfoMap[gid]
                if (info == null) {
                    Log.d(TAG, "Can't get download info with gid: $gid")
                    i--
                    continue
                }

                if (info.state == DownloadInfo.STATE_NONE ||
                    info.state == DownloadInfo.STATE_FAILED ||
                    info.state == DownloadInfo.STATE_FINISH
                ) {
                    update = true
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT
                    // Add to wait list
                    mWaitList.add(info)
                    // Update in DB
                    scope.launch { EhDB.putDownloadInfoAsync(info) }
                }
                i--
            }
        }

        if (update) {
            // Notify Listener
            eventBus.forEachListener { it.onUpdateAll() }
            // Ensure download
            ensureDownload()
        }
    }

    fun startAllDownload() {
        assertMainThread()
        var update = false
        // Start all STATE_NONE and STATE_FAILED item
        val allInfoList = mAllInfoList
        val waitList = mWaitList
        val downloadOrder = DownloadSettings.getDownloadOrder()
        if (downloadOrder) {
            for (info in allInfoList) {
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                    update = true
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT
                    // Add to wait list
                    waitList.add(info)
                    // Update in DB
                    scope.launch { EhDB.putDownloadInfoAsync(info) }
                }
            }
        } else {
            for (info in allInfoList) {
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                    update = true
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT
                    // Add to wait list
                    waitList.add(0, info)
                    // Update in DB
                    scope.launch { EhDB.putDownloadInfoAsync(info) }
                }
            }
        }

        if (update) {
            // Notify Listener
            eventBus.forEachListener { it.onUpdateAll() }
            // Ensure download
            ensureDownload()
        }
    }

    fun addDownload(downloadInfoList: List<DownloadInfo>) {
        assertMainThread()
        val newLabelsToAdd = mutableListOf<String>()
        for (info in downloadInfoList) {
            if (containDownloadInfo(info.gid)) {
                continue
            }

            if (DownloadInfo.STATE_WAIT == info.state ||
                DownloadInfo.STATE_DOWNLOAD == info.state
            ) {
                info.state = DownloadInfo.STATE_NONE
            }

            var list = getInfoListForLabel(info.label)
            if (list == null) {
                list = ArrayList()
                mMap[info.label] = list
                if (!containLabel(info.label) && info.label != null) {
                    newLabelsToAdd.add(info.label!!)
                }
            }
            insertSorted(list, info)

            insertSorted(mAllInfoList, info)
            mAllInfoMap[info.gid] = info
        }

        // Persist to DB on background thread. The DB writes happen here, but the
        // resulting label rows must be published into the main-thread-only
        // mLabelList/mLabelSet via runOnMainThread, not from the IO segment.
        val infosToSave = ArrayList(downloadInfoList)
        val labelStringsToPersist = ArrayList(newLabelsToAdd)
        scope.launch {
            val savedLabels = ArrayList<DownloadLabel>(labelStringsToPersist.size)
            for (label in labelStringsToPersist) {
                savedLabels.add(EhDB.addDownloadLabelAsync(label))
            }
            for (info in infosToSave) {
                EhDB.putDownloadInfoAsync(info)
            }
            if (savedLabels.isNotEmpty()) {
                runOnMainThread {
                    for (saved in savedLabels) {
                        mLabelList.add(saved)
                        saved.label?.let { mLabelSet.add(it) }
                    }
                }
            }
        }

        // Notify on main thread
        eventBus.postToMain {
            eventBus.forEachListener {
                it.onReload()
            }
        }
    }

    fun addDownloadLabel(downloadLabelList: List<DownloadLabel>) {
        assertMainThread()
        val labelsToAdd = mutableListOf<DownloadLabel>()
        for (label in downloadLabelList) {
            val labelString = label.label
            if (!containLabel(labelString)) {
                mMap[labelString] = ArrayList()
                labelsToAdd.add(label)
            }
        }
        if (labelsToAdd.isNotEmpty()) {
            // The IO segment only writes the DB; the resulting label rows are
            // published to the main-thread-only mLabelList/mLabelSet via
            // runOnMainThread.
            scope.launch {
                val savedLabels = ArrayList<DownloadLabel>(labelsToAdd.size)
                for (label in labelsToAdd) {
                    savedLabels.add(EhDB.addDownloadLabelAsync(label))
                }
                runOnMainThread {
                    for (saved in savedLabels) {
                        mLabelList.add(saved)
                        saved.label?.let { mLabelSet.add(it) }
                    }
                }
            }
        }
    }

    fun addDownload(galleryInfo: GalleryInfo, label: String?, state: Int) {
        assertMainThread()
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return
        }

        // It is new download info
        val info = DownloadInfo(galleryInfo)
        info.label = label
        info.state = state
        info.time = System.currentTimeMillis()

        // Add to label download list
        val list = getInfoListForLabel(info.label)
        if (!mLabelCountMap.containsKey(label)) {
            mLabelCountMap[label] = 1L
        } else {
            val value = (mLabelCountMap[label] ?: 0L) + 1L
            mLabelCountMap[label] = value
        }
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: $label")
            return
        }
        list.add(0, info)

        // Add to all download list and map
        mAllInfoList.add(0, info)
        mAllInfoMap[galleryInfo.gid] = info

        // Save to DB
        scope.launch { EhDB.putDownloadInfoAsync(info) }

        // Notify
        eventBus.forEachListener { it.onAdd(info, list, list.size - 1) }
    }

    fun addDownload(galleryInfo: GalleryInfo, label: String?) {
        addDownload(galleryInfo, label, DownloadInfo.STATE_NONE)
    }

    fun addDownloadInfo(galleryInfo: GalleryInfo, label: String?) {
        assertMainThread()
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return
        }

        // It is new download info
        val info = DownloadInfo(galleryInfo)
        info.label = label
        info.state = DownloadInfo.STATE_NONE
        if (info.time == 0L) {
            info.time = System.currentTimeMillis()
        }

        // Add to label download list
        val list = getInfoListForLabel(info.label)
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: $label")
            return
        }
        list.add(0, info)

        // Save to DB
        scope.launch { EhDB.putDownloadInfoAsync(info) }
        mAllInfoMap[galleryInfo.gid] = info
    }

    fun stopDownload(gid: Long) {
        assertMainThread()
        val info = stopDownloadInternal(gid)
        if (info != null) {
            // Update listener
            val list = getInfoListForLabel(info.label)
            if (list != null) {
                eventBus.forEachListener { it.onUpdate(info, list, mWaitList) }
            }
            // Ensure download
            ensureDownload()
        }
    }

    fun stopCurrentDownload() {
        assertMainThread()
        val info = stopCurrentDownloadInternal()
        if (info != null) {
            // Update listener
            val list = getInfoListForLabel(info.label)
            if (list != null) {
                eventBus.forEachListener { it.onUpdate(info, list, mWaitList) }
            }
            // Ensure download
            ensureDownload()
        }
    }

    fun stopRangeDownload(gidList: LongList) {
        assertMainThread()
        stopRangeDownloadInternal(gidList)

        // Update listener
        eventBus.forEachListener { it.onUpdateAll() }

        // Ensure download
        ensureDownload()
    }

    fun stopAllDownload() {
        assertMainThread()
        // Stop all in wait list
        for (info in mWaitList) {
            info.state = DownloadInfo.STATE_NONE
            // Update in DB
            scope.launch { EhDB.putDownloadInfoAsync(info) }
        }
        mWaitList.clear()

        // Stop current
        stopCurrentDownloadInternal()

        // Notify mDownloadInfoListener
        eventBus.forEachListener { it.onUpdateAll() }
    }

    fun deleteDownload(gid: Long) {
        assertMainThread()
        stopDownloadInternal(gid)
        val info = mAllInfoMap[gid]
        if (info != null) {
            // Remove all list and map
            mAllInfoList.remove(info)
            mAllInfoMap.remove(info.gid)

            // Remove label list
            val list = getInfoListForLabel(info.label)
            if (list != null) {
                val index = list.indexOf(info)
                if (index >= 0) {
                    list.removeAt(index)
                    // Update listener
                    eventBus.forEachListener {
                        it.onRemove(info, list, index)
                    }
                }
            }

            // Remove from DB on background thread
            val gidToRemove = info.gid
            scope.launch { EhDB.removeDownloadInfoAsync(gidToRemove) }

            // Ensure download
            ensureDownload()
        }
    }

    fun deleteRangeDownload(gidList: LongList) {
        assertMainThread()
        stopRangeDownloadInternal(gidList)

        // Collect gids into a HashSet for O(1) lookup
        val gidSet = HashSet<Long>(gidList.size())
        for (i in 0 until gidList.size()) {
            gidSet.add(gidList.get(i))
        }

        // Collect infos to remove and clear from map
        val gidsToRemove = mutableListOf<Long>()
        for (gid in gidSet) {
            val info = mAllInfoMap.remove(gid)
            if (info == null) {
                Log.d(TAG, "Can't get download info with gid: $gid")
                continue
            }
            gidsToRemove.add(info.gid)

            // Remove from label list
            val list = getInfoListForLabel(info.label)
            list?.remove(info)
        }

        // O(N) removal from mAllInfoList using removeAll
        mAllInfoList.removeAll { it.gid in gidSet }

        // Remove from DB on background thread
        if (gidsToRemove.isNotEmpty()) {
            scope.launch { EhDB.removeDownloadInfoBatchAsync(gidsToRemove) }
        }

        // Update listener
        eventBus.forEachListener { it.onReload() }

        // Ensure download
        ensureDownload()
    }

    fun resetAllReadingProgress() {
        assertMainThread()
        val list = ArrayList(mAllInfoList)

        scope.launch {
            val galleryInfo = GalleryInfo()
            for (downloadInfo in list) {
                galleryInfo.gid = downloadInfo.gid
                galleryInfo.token = downloadInfo.token
                galleryInfo.title = downloadInfo.title
                galleryInfo.thumb = downloadInfo.thumb
                galleryInfo.category = downloadInfo.category
                galleryInfo.posted = downloadInfo.posted
                galleryInfo.uploader = downloadInfo.uploader
                galleryInfo.rating = downloadInfo.rating

                val downloadDir = SpiderDen.getGalleryDownloadDir(galleryInfo) ?: continue
                val file = downloadDir.findFile(".ehviewer") ?: continue
                val spiderInfo = SpiderInfo.read(file) ?: continue
                spiderInfo.startPage = 0

                try {
                    spiderInfo.write(file.openOutputStream())
                } catch (e: IOException) {
                    Log.e(TAG, "Can't write SpiderInfo", e)
                }
            }
        }
    }

    // Update in DB
    // Update listener
    // No ensureDownload
    private fun stopDownloadInternal(gid: Long): DownloadInfo? {
        // Check active tasks
        val activeIt = mActiveTasks.iterator()
        while (activeIt.hasNext()) {
            val info = activeIt.next()
            if (info.gid == gid) {
                val w = mActiveWorkers.remove(info)
                w?.cancel()
                activeIt.remove()
                if (mActiveTasks.isEmpty()) mSpeedReminder.stop()
                info.state = DownloadInfo.STATE_NONE
                scope.launch { EhDB.putDownloadInfoAsync(info) }
                eventBus.getDownloadListener()?.onCancel(info)
                return info
            }
        }

        val waitIt = mWaitList.iterator()
        while (waitIt.hasNext()) {
            val info = waitIt.next()
            if (info.gid == gid) {
                // Remove from wait list
                waitIt.remove()
                // Update state
                info.state = DownloadInfo.STATE_NONE
                // Update in DB
                scope.launch { EhDB.putDownloadInfoAsync(info) }
                return info
            }
        }
        return null
    }

    // Update in DB
    // Update download listener
    private fun stopCurrentDownloadInternal(): DownloadInfo? {
        // Cancel all active workers
        for (w in mActiveWorkers.values) {
            w.cancel()
        }
        val stopped = ArrayList(mActiveTasks)
        mActiveTasks.clear()
        mActiveWorkers.clear()
        mSpeedReminder.stop()
        if (stopped.isEmpty()) return null
        for (info in stopped) {
            info.state = DownloadInfo.STATE_NONE
            scope.launch { EhDB.putDownloadInfoAsync(info) }
            eventBus.getDownloadListener()?.onCancel(info)
        }
        return stopped[0]
    }

    // Update in DB
    // Update download listener
    private fun stopRangeDownloadInternal(gidList: LongList) {
        // Two way
        if (gidList.size() < mWaitList.size) {
            for (i in 0 until gidList.size()) {
                stopDownloadInternal(gidList.get(i))
            }
        } else {
            // Check active tasks
            for (active in ArrayList(mActiveTasks)) {
                if (gidList.contains(active.gid)) {
                    stopDownloadInternal(active.gid)
                }
            }

            // Check all in wait list
            val iterator = mWaitList.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                if (gidList.contains(info.gid)) {
                    // Remove from wait list
                    iterator.remove()
                    // Update state
                    info.state = DownloadInfo.STATE_NONE
                    // Update in DB
                    scope.launch { EhDB.putDownloadInfoAsync(info) }
                }
            }
        }
    }

    /**
     * @param label Not allow new label
     */
    fun changeLabel(list: List<DownloadInfo>, label: String?) {
        assertMainThread()
        if (label != null && !containLabel(label)) {
            Log.e(TAG, "Not exits label: $label")
            return
        }

        val dstList = getInfoListForLabel(label)
        if (dstList == null) {
            Log.e(TAG, "Can't find label with label: $label")
            return
        }

        for (info in list) {
            if (ObjectUtils.equal(info.label, label)) {
                continue
            }

            val srcList = getInfoListForLabel(info.label)
            if (srcList == null) {
                Log.e(TAG, "Can't find label with label: " + info.label)
                continue
            }

            srcList.remove(info)
            info.label = label
            insertSorted(dstList, info)

            // Save to DB
            scope.launch { EhDB.putDownloadInfoAsync(info) }
        }

        eventBus.forEachListener { it.onReload() }
    }

    fun addLabel(label: String?) {
        assertMainThread()
        if (label == null || containLabel(label)) {
            return
        }

        // Create a placeholder label in memory immediately so the UI gets a
        // synchronous update via onUpdateLabels below.
        val newLabel = DownloadLabel().apply {
            this.label = label
            this.time = System.currentTimeMillis()
        }
        mLabelList.add(newLabel)
        mLabelSet.add(label)
        mMap[label] = ArrayList()

        // Persist to DB on background thread, then update the placeholder's
        // id/time fields back on the main thread. The previous code did the
        // field assignments inside the IO coroutine, racing with main-thread
        // readers of the same DownloadLabel object.
        scope.launch {
            val saved = EhDB.addDownloadLabelAsync(label)
            runOnMainThread {
                newLabel.id = saved.id
                newLabel.time = saved.time
            }
        }

        eventBus.forEachListener {
            it.onUpdateLabels()
        }
    }

    fun moveLabel(fromPosition: Int, toPosition: Int) {
        assertMainThread()
        val item = mLabelList.removeAt(fromPosition)
        mLabelList.add(toPosition, item)

        scope.launch { EhDB.moveDownloadLabelAsync(fromPosition, toPosition) }

        eventBus.forEachListener {
            it.onUpdateLabels()
        }
    }

    fun renameLabel(from: String, to: String) {
        assertMainThread()
        // Find in label list
        var rawLabel: DownloadLabel? = null
        for (raw in mLabelList) {
            if (from == raw.label) {
                rawLabel = raw
                raw.label = to
                break
            }
        }
        if (rawLabel == null) {
            return
        }

        // Update label set
        mLabelSet.remove(from)
        mLabelSet.add(to)

        val list = mMap.remove(from) ?: return

        // Update info label
        for (info in list) {
            info.label = to
        }
        // Put list back with new label
        mMap[to] = list

        // Persist to DB on background thread
        val labelToUpdate = rawLabel
        val infosToUpdate = ArrayList(list)
        scope.launch {
            EhDB.updateDownloadLabelAsync(labelToUpdate)
            EhDB.putDownloadInfoBatchAsync(infosToUpdate)
        }

        // Notify listener
        eventBus.forEachListener {
            it.onRenameLabel(from, to)
        }
    }

    fun deleteLabel(label: String) {
        assertMainThread()
        // Find in label list and remove
        var removedLabel: DownloadLabel? = null
        val iterator = mLabelList.iterator()
        while (iterator.hasNext()) {
            val raw = iterator.next()
            if (label == raw.label) {
                removedLabel = raw
                iterator.remove()
                break
            }
        }
        if (removedLabel == null) {
            return
        }

        // Update label set
        mLabelSet.remove(label)

        val list = mMap.remove(label) ?: return

        // Update info label and insert into default list in sorted order
        for (info in list) {
            info.label = null
            insertSorted(mDefaultInfoList, info)
        }

        // Persist to DB on background thread
        val labelToRemove = removedLabel
        val infosToUpdate = ArrayList(list)
        scope.launch {
            EhDB.removeDownloadLabelAsync(labelToRemove)
            EhDB.putDownloadInfoBatchAsync(infosToUpdate)
        }

        // Notify listener
        eventBus.forEachListener {
            it.onChange()
        }
    }

    val isIdle: Boolean
        get() {
            assertMainThread()
            return mActiveTasks.isEmpty() && mWaitList.isEmpty()
        }

    /**
     * Sealed interface for immutable download events dispatched from worker threads.
     * Replaces the mutable [NotifyTask] + [ConcurrentPool] pattern.
     */
    private sealed interface DownloadEvent {
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
     * Dispatch a download event on the main thread.
     * This replaces the old NotifyTask.run() + handler.post() pattern.
     */
    private fun dispatchEvent(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.OnGetPages -> {
                val info = event.taskInfo
                info.total = event.pages
                val list = getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener {
                        it.onUpdate(info, list, mWaitList)
                    }
                }
            }
            is DownloadEvent.OnGet509 -> {
                eventBus.getDownloadListener()?.onGet509()
            }
            is DownloadEvent.OnPageDownload -> {
                mSpeedReminder.onDownload(event.index, event.contentLength, event.receivedSize, event.bytesRead)
            }
            is DownloadEvent.OnPageSuccess -> {
                mSpeedReminder.onDone(event.index)
                val info = event.taskInfo
                info.finished = event.finished
                info.downloaded = event.downloaded
                info.total = event.total
                eventBus.getDownloadListener()?.onGetPage(info)
                val list = getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener {
                        it.onUpdate(info, list, mWaitList)
                    }
                }
            }
            is DownloadEvent.OnPageFailure -> {
                mSpeedReminder.onDone(event.index)
                val info = event.taskInfo
                info.finished = event.finished
                info.downloaded = event.downloaded
                info.total = event.total
                val list = getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener {
                        it.onUpdate(info, list, mWaitList)
                    }
                }
            }
            is DownloadEvent.OnFinish -> {
                mSpeedReminder.onFinish()
                val info = event.taskInfo
                mActiveTasks.remove(info)
                mActiveWorkers.remove(info)
                if (mActiveTasks.isEmpty()) mSpeedReminder.stop()
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
                // Update in DB
                scope.launch { EhDB.putDownloadInfoAsync(info) }
                // Notify
                eventBus.getDownloadListener()?.onFinish(info)
                val list = getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener {
                        it.onUpdate(info, list, mWaitList)
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

    private inner class PerTaskListener(private val mInfo: DownloadInfo) : SpiderQueen.OnSpiderListener {

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

        override fun onGetImageSuccess(index: Int, image: Image) {}

        override fun onGetImageFailure(index: Int, error: String) {}
    }

    companion object {
        private val TAG = DownloadManager::class.java.simpleName

        const val DOWNLOAD_INFO_FILENAME = ".ehviewer"
        const val DOWNLOAD_INFO_HEADER = "gid,token,title,title_jpn,thumb,category,posted,uploader,rating,rated,simple_lang,simple_tags,thumb_width,thumb_height,span_size,span_index,span_group_index,favorite_slot,favorite_name,pages"

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
