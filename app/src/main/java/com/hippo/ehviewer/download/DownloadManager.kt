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
import android.util.Log
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.mapper.toDownloadInfoView
import com.lanraragi.reader.domain.parseRatingFromTags
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.lib.yorozuya.ObjectUtils
import com.lanraragi.reader.domain.Archive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * Thin facade over [DownloadRepository], [DownloadScheduler], and
 * [DownloadEventBus]. All collection state lives in [repo], all worker
 * lifecycle in [scheduler], all listener management in [eventBus].
 */
class DownloadManager(
    private val mContext: Context,
    private val scope: CoroutineScope = ServiceRegistry.coroutineModule.ioScope,
    internal val eventBus: DownloadEventBus = DownloadEventBus()
) {

    internal val repo = DownloadRepository(mContext, scope)

    /**
     * In-memory per-archive progress snapshot (W35-3a, ADR-001 Option D).
     * New code should read progress via [progressFor] or subscribe to
     * `progressTracker.progressFlow` instead of reading transient fields
     * from [DownloadInfo] directly.
     */
    val progressTracker = DownloadProgressTracker()

    private val mSpeedReminder: DownloadSpeedTracker
    internal lateinit var scheduler: DownloadScheduler

    init {
        mSpeedReminder = DownloadSpeedTracker(object : DownloadSpeedTracker.Callback {
            override fun getActiveTasks(): List<DownloadInfo> = scheduler.activeTasks.toList()
            override fun getInfoListForLabel(label: String?): List<DownloadInfo>? = repo.getInfoListForLabel(label)
            override fun getDownloadListener(): DownloadListener? = eventBus.getDownloadListener()
            override fun getDownloadInfoListeners(): List<WeakReference<DownloadInfoListener>> = eventBus.getInfoListenerRefs()
            override fun getWaitList(): List<DownloadInfo> = scheduler.waitList
        }, progressTracker)
        scheduler = DownloadScheduler(mContext, scope, repo, eventBus, mSpeedReminder, progressTracker)
        repo.startLoading {
            eventBus.forEachListener { it.onReload() }
            syncRatingsFromServer()
        }
    }

    /** @return the current progress snapshot for [arcid], or null if not tracked. */
    fun progressFor(arcid: String): ProgressSnapshot? = progressTracker.snapshot(arcid)

    /**
     * After initial load from DB, fetch current ratings from the server
     * for all downloaded archives and update local DB where they differ.
     * Runs entirely in the background; UI updates via DownloadInfoListener
     * are fired for any item whose rating changed.
     */
    private fun syncRatingsFromServer() {
        scope.launch {
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                val cache = ServiceRegistry.dataModule.profileLookupCache

                // Snapshot the list on main thread
                val infos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    ArrayList(repo.allInfoList)
                }
                if (infos.isEmpty()) return@launch

                // Fetch metadata for each archive and collect rating changes.
                val changes = ArrayList<Pair<DownloadInfo, Float>>()
                for (info in infos) {
                    val arcid = info.arcid ?: continue
                    try {
                        // Route to the archive's *source* server (resolved from its
                        // serverProfileId), not the active one — a cross-profile download
                        // otherwise hits the wrong server and, since arcids are content
                        // hashes, could read another server's rating for the same id.
                        val baseUrl = com.lanraragi.reader.client.api
                            .resolveSourceBaseUrl(info.serverProfileId, cache)
                        val archive = com.lanraragi.reader.client.api.LRRArchiveApi
                            .getArchiveMetadata(client, baseUrl, arcid)
                        val serverRating = parseRatingFromTags(archive.tags)
                        // Only update if server has a meaningful rating that
                        // differs from the local value. -1 = no rating tag on
                        // server, 0 = unrated locally; treat both as "unrated".
                        val localEffective = if (info.rating <= 0) -1f else info.rating
                        val serverEffective = if (serverRating <= 0) -1f else serverRating
                        if (serverEffective != localEffective) {
                            changes.add(info to if (serverRating < 0) 0f else serverRating)
                        }
                    } catch (e: Exception) {
                        // Skip this archive on error, continue with next
                    }
                }

                if (changes.isEmpty()) return@launch

                // DownloadInfo is main-thread-owned: mutate on Main, then persist + notify.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    for ((info, rating) in changes) info.rating = rating
                }
                for ((info, _) in changes) {
                    ServiceRegistry.dataModule.downloadDbRepository.putDownloadInfo(info)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    eventBus.forEachListener { it.onChange() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Background rating sync failed", e)
                Analytics.recordException(e)
            }
        }
    }

    suspend fun awaitInitAsync(timeoutMs: Long = 10_000L) {
        if (repo.initialized) return
        check(Looper.myLooper() != Looper.getMainLooper()) { "awaitInitAsync() must not be called on the main thread" }
        kotlinx.coroutines.withTimeout(timeoutMs) { repo.initDeferred.await() }
    }

    // ── Query methods ─────────────────────────────────────────

    fun replaceInfo(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
        repo.replaceInfo(newInfo, oldInfo)
        eventBus.forEachListener { it.onReplace(newInfo, oldInfo) }
    }

    fun containLabel(label: String?): Boolean = repo.containLabel(label)
    fun containDownloadInfo(arcid: String): Boolean = repo.containDownloadInfo(arcid)
    fun getDownloadInfo(arcid: String): DownloadInfo? = repo.getDownloadInfo(arcid)
    fun getDownloadState(arcid: String): DownloadState = repo.getDownloadState(arcid)
    fun getLabelCount(label: String?): Long = repo.getLabelCount(label)
    fun getNoneDownloadInfo(arcid: String): DownloadInfo? = scheduler.getNoneDownloadInfo(arcid)
    val isIdle: Boolean get() = scheduler.isIdle

    val labelList: List<DownloadLabel> get() { repo.assertMainThread(); return repo.labelList }
    val allDownloadInfoList: List<DownloadInfo> get() {
        repo.assertMainThread()
        return Collections.unmodifiableList(repo.allInfoList)
    }

    val defaultDownloadInfoList: List<DownloadInfo> get() {
        repo.assertMainThread()
        return Collections.unmodifiableList(repo.defaultInfoList)
    }

    val downloadInfoList: List<DownloadInfo> get() { repo.assertMainThread(); return ArrayList(repo.allInfoList) }

    fun getLabelDownloadInfoList(label: String?): List<DownloadInfo>? {
        repo.assertMainThread()
        val list = repo.labelInfoMap[label] ?: return null
        return Collections.unmodifiableList(list)
    }

    // ── Listener methods ──────────────────────────────────────

    fun addDownloadInfoListener(listener: DownloadInfoListener) { eventBus.addDownloadInfoListener(listener) }
    fun removeDownloadInfoListener(listener: DownloadInfoListener) { eventBus.removeDownloadInfoListener(listener) }
    fun setDownloadListener(listener: DownloadListener?) { eventBus.setDownloadListener(listener) }

    // ── Download lifecycle ────────────────────────────────────

    fun startDownload(archive: Archive, label: String?) {
        repo.assertMainThread()
        // Restarting a download clears any stale "paused/timed out" resume-banner entry,
        // otherwise the next foreground would show a ghost banner for an active download.
        DownloadResumeBanner.markResumed(archive.arcid)
        for (active in scheduler.activeTasks) { if (active.arcid == archive.arcid) return }
        // Enforces the composite-key download invariant ("<=1 download row per
        // arcid"): an arcid already present is re-used, never re-added under a
        // second profile. arcid is a content hash, so a mirror copy on another
        // profile shares the same single local download row + on-disk dir.
        val existing = repo.getDownloadInfo(archive.arcid)
        if (existing != null) {
            // Imported archives (content:// URIs) cannot be re-downloaded; the
            // pre-W36-4 short-circuit on `galleryInfo is DownloadInfo` is now
            // anchored on the persisted state, which is the only place
            // archiveUri actually lives.
            val uri = existing.archiveUri
            if (uri != null && uri.startsWith("content://")) return
            if (existing.state != DownloadState.WAIT) {
                existing.state = DownloadState.WAIT
                scheduler.waitList.add(existing)
                repo.persistInfo(existing)
                val list = repo.getInfoListForLabel(existing.label)
                if (list != null) eventBus.forEachListener { it.onUpdate(existing, list, scheduler.waitList) }
                scheduler.ensureDownload()
            }
        } else {
            val info = archive.toDownloadInfoView().apply {
                this.label = label
                state = DownloadState.WAIT
                time = System.currentTimeMillis()
                downloadRootUri = DownloadSettings.getCurrentDownloadRootUri()
            }
            val list = repo.getInfoListForLabel(info.label) ?: run { Log.e(TAG, "Can't find download info list with label: $label"); return }
            list.add(0, info)
            repo.allInfoList.add(0, info)
            repo.allInfoMap[archive.arcid] = info
            scheduler.waitList.add(info)
            repo.persistInfo(info)
            // Inserted at index 0 (newest-first) above, so the listener
            // position is 0 — not list.size - 1, which pointed at the wrong row.
            eventBus.forEachListener { it.onAdd(info, list, 0) }
            scheduler.ensureDownload()
            repo.persistHistory(info)
        }
    }

    fun startRangeDownload(arcidList: List<String>) {
        repo.assertMainThread()
        var update = false
        val downloadOrder = DownloadSettings.getDownloadOrder()
        if (downloadOrder) {
            for (arcid in arcidList) {
                val info = repo.allInfoMap[arcid] ?: continue
                if (info.state == DownloadState.NONE || info.state == DownloadState.FAILED || info.state == DownloadState.FINISH) {
                    DownloadResumeBanner.markResumed(arcid)
                    update = true; info.state = DownloadState.WAIT; scheduler.waitList.add(info); repo.persistInfo(info)
                }
            }
        } else {
            for (arcid in arcidList.reversed()) {
                val info = repo.allInfoMap[arcid] ?: continue
                if (info.state == DownloadState.NONE || info.state == DownloadState.FAILED || info.state == DownloadState.FINISH) {
                    DownloadResumeBanner.markResumed(arcid)
                    update = true; info.state = DownloadState.WAIT; scheduler.waitList.add(info); repo.persistInfo(info)
                }
            }
        }
        if (update) { eventBus.forEachListener { it.onUpdateAll() }; scheduler.ensureDownload() }
    }

    fun startAllDownload() {
        repo.assertMainThread()
        var update = false
        val downloadOrder = DownloadSettings.getDownloadOrder()
        for (info in repo.allInfoList) {
            if (info.state == DownloadState.NONE || info.state == DownloadState.FAILED) {
                DownloadResumeBanner.markResumed(info.arcid)
                update = true; info.state = DownloadState.WAIT
                if (downloadOrder) scheduler.waitList.add(info) else scheduler.waitList.add(0, info)
                repo.persistInfo(info)
            }
        }
        if (update) { eventBus.forEachListener { it.onUpdateAll() }; scheduler.ensureDownload() }
    }

    fun addDownload(downloadInfoList: List<DownloadInfo>) {
        repo.assertMainThread()
        val newLabels = repo.importInfoBatch(downloadInfoList)
        val infosToSave = ArrayList(downloadInfoList)
        val labelsToPersist = ArrayList(newLabels)
        scope.launch {
            try {
                val savedLabels = ArrayList<DownloadLabel>(labelsToPersist.size)
                for (l in labelsToPersist) savedLabels.add(ServiceRegistry.dataModule.downloadDbRepository.addDownloadLabel(l))
                for (info in infosToSave) ServiceRegistry.dataModule.downloadDbRepository.putDownloadInfo(info)
                if (savedLabels.isNotEmpty()) {
                    repo.runOnMainThread { for (s in savedLabels) { repo.labelList.add(s); s.label?.let { repo.labelSet.add(it) } } }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist imported downloads", e)
            }
        }
        eventBus.postToMain { eventBus.forEachListener { it.onReload() } }
    }

    fun addDownloadLabel(downloadLabelList: List<DownloadLabel>) {
        repo.assertMainThread()
        val toAdd = repo.importLabelBatch(downloadLabelList)
        if (toAdd.isNotEmpty()) {
            scope.launch {
                try {
                    val saved = ArrayList<DownloadLabel>(toAdd.size)
                    for (l in toAdd) saved.add(ServiceRegistry.dataModule.downloadDbRepository.addDownloadLabel(l))
                    repo.runOnMainThread { for (s in saved) { repo.labelList.add(s); s.label?.let { repo.labelSet.add(it) } } }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist imported download labels", e)
                }
            }
        }
    }

    fun addDownload(archive: Archive, label: String?, state: DownloadState) {
        repo.assertMainThread()
        val result = repo.addSingleDownload(archive, label, state) ?: return
        // addSingleDownload inserts at index 0 (newest-first); report that
        // position instead of size - 1.
        eventBus.forEachListener { it.onAdd(result.first, result.second, 0) }
    }

    fun addDownload(archive: Archive, label: String?) { addDownload(archive, label, DownloadState.NONE) }

    fun addDownloadInfo(archive: Archive, label: String?) {
        repo.assertMainThread()
        repo.addInfoOnly(archive, label)
    }

    // ── Stop / Delete ─────────────────────────────────────────

    fun stopDownload(arcid: String) {
        repo.assertMainThread()
        val info = scheduler.stopDownload(arcid) ?: return
        val list = repo.getInfoListForLabel(info.label)
        if (list != null) eventBus.forEachListener { it.onUpdate(info, list, scheduler.waitList) }
        scheduler.ensureDownload()
    }

    fun stopCurrentDownload() {
        repo.assertMainThread()
        val info = scheduler.stopCurrentDownload() ?: return
        val list = repo.getInfoListForLabel(info.label)
        if (list != null) eventBus.forEachListener { it.onUpdate(info, list, scheduler.waitList) }
        scheduler.ensureDownload()
    }

    fun stopRangeDownload(arcidList: List<String>) {
        repo.assertMainThread()
        scheduler.stopRangeDownload(arcidList)
        eventBus.forEachListener { it.onUpdateAll() }
        scheduler.ensureDownload()
    }

    fun stopAllDownload() {
        repo.assertMainThread()
        scheduler.stopAllDownload()
        eventBus.forEachListener { it.onUpdateAll() }
    }

    fun deleteDownload(arcid: String) {
        repo.assertMainThread()
        scheduler.stopDownload(arcid)
        // scheduler.stopDownload only clears the resume banner for active/waiting
        // tasks; a timed-out (FAILED) download is neither, so clear it here too.
        DownloadResumeBanner.markResumed(arcid)
        val result = repo.deleteInfo(arcid) ?: return
        val (info, list, index) = result
        if (index >= 0) eventBus.forEachListener { it.onRemove(info, list, index) }
        scheduler.ensureDownload()
    }

    fun deleteRangeDownload(arcidList: List<String>) {
        repo.assertMainThread()
        scheduler.stopRangeDownload(arcidList)
        repo.deleteInfoRange(arcidList.toHashSet())
        arcidList.forEach { DownloadResumeBanner.markResumed(it) }
        eventBus.forEachListener { it.onReload() }
        scheduler.ensureDownload()
    }

    // ── Reload ────────────────────────────────────────────────

    fun reload() {
        repo.assertMainThread()
        stopAllDownload()
        repo.reload { eventBus.forEachListener { it.onReload() } }
    }

    // ── Misc ──────────────────────────────────────────────────

    fun resetAllReadingProgress() {
        repo.assertMainThread()
        val list = ArrayList(repo.allInfoList)
        scope.launch {
            try {
                for (di in list) {
                    val dir = SpiderDen.getGalleryDownloadDir(di.arcid, di.title) ?: continue
                    val file = dir.findFile(".ehviewer") ?: continue
                    val si = SpiderInfo.read(file) ?: continue
                    si.startPage = 0
                    try {
                        file.openOutputStream()?.use { os -> si.write(os) }
                    } catch (e: IOException) {
                        Log.e(TAG, "Can't write SpiderInfo", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset reading progress", e)
            }
        }
    }

    // ── Label CRUD ────────────────────────────────────────────

    fun changeLabel(list: List<DownloadInfo>, label: String?) {
        repo.assertMainThread()
        if (label != null && !repo.containLabel(label)) { Log.e(TAG, "Not exits label: $label"); return }
        val dstList = repo.getInfoListForLabel(label) ?: run { Log.e(TAG, "Can't find label with label: $label"); return }
        for (info in list) {
            if (ObjectUtils.equal(info.label, label)) continue
            val srcList = repo.getInfoListForLabel(info.label)
            if (srcList == null) { Log.e(TAG, "Can't find label with label: " + info.label); continue }
            srcList.remove(info); info.label = label; DownloadRepository.insertSorted(dstList, info); repo.persistInfo(info)
        }
        eventBus.forEachListener { it.onReload() }
    }

    fun addLabel(label: String?) {
        repo.assertMainThread()
        if (label == null || repo.containLabel(label)) return
        repo.addLabel(label)
        eventBus.forEachListener { it.onUpdateLabels() }
    }

    fun moveLabel(fromPosition: Int, toPosition: Int) {
        repo.assertMainThread(); repo.moveLabel(fromPosition, toPosition)
        eventBus.forEachListener { it.onUpdateLabels() }
    }

    fun renameLabel(from: String, to: String) {
        repo.assertMainThread()
        repo.renameLabel(from, to) ?: return
        eventBus.forEachListener { it.onRenameLabel(from, to) }
    }

    fun deleteLabel(label: String) {
        repo.assertMainThread()
        repo.deleteLabel(label) ?: return
        eventBus.forEachListener { it.onChange() }
    }

    companion object {
        private val TAG = DownloadManager::class.java.simpleName
        const val DOWNLOAD_INFO_FILENAME = ".ehviewer"
        const val DOWNLOAD_INFO_HEADER = "gid,token,title,title_jpn,thumb,category,posted,uploader,rating,rated,simple_lang,simple_tags,thumb_width,thumb_height,span_size,span_index,span_group_index,favorite_slot,favorite_name,pages"
        @JvmField val DATE_DESC_COMPARATOR: Comparator<DownloadInfo> = Comparator { lhs, rhs ->
            val dif = lhs.time - rhs.time
            when { dif > 0 -> -1; dif < 0 -> 1; else -> 0 }
        }
        internal fun insertSorted(list: MutableList<DownloadInfo>, item: DownloadInfo) { DownloadRepository.insertSorted(list, item) }
    }
}
