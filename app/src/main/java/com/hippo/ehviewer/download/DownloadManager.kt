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
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.lib.yorozuya.ObjectUtils
import com.hippo.lib.yorozuya.collect.LongList
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
    private val mSpeedReminder: DownloadSpeedTracker
    internal lateinit var scheduler: DownloadScheduler

    init {
        mSpeedReminder = DownloadSpeedTracker(object : DownloadSpeedTracker.Callback {
            override fun getFirstActiveTask(): DownloadInfo? = scheduler.activeTasks.firstOrNull()
            override fun getInfoListForLabel(label: String?): List<DownloadInfo>? = repo.getInfoListForLabel(label)
            override fun getDownloadListener(): DownloadListener? = eventBus.getDownloadListener()
            override fun getDownloadInfoListeners(): List<WeakReference<DownloadInfoListener>> = eventBus.getInfoListenerRefs()
            override fun getWaitList(): List<DownloadInfo> = scheduler.waitList
        })
        scheduler = DownloadScheduler(mContext, scope, repo, eventBus, mSpeedReminder)
        repo.startLoading {
            eventBus.forEachListener { it.onReload() }
            syncRatingsFromServer()
        }
    }

    /**
     * After initial load from DB, fetch current ratings from the server
     * for all downloaded archives and update local DB where they differ.
     * Runs entirely in the background; UI updates via DownloadInfoListener
     * are fired for any item whose rating changed.
     */
    private fun syncRatingsFromServer() {
        scope.launch {
            try {
                val serverUrl = com.lanraragi.reader.client.api.LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                // Snapshot the list on main thread
                val infos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    ArrayList(repo.allInfoList)
                }
                if (infos.isEmpty()) return@launch

                // Fetch metadata for each archive and compare ratings
                for (info in infos) {
                    val arcid = info.token ?: continue
                    try {
                        val archive = com.lanraragi.reader.client.api.LRRArchiveApi
                            .getArchiveMetadata(client, serverUrl, arcid)
                        val serverRating = com.lanraragi.reader.client.api.data.LRRArchive
                            .parseRatingFromTags(archive.tags)
                        // Only update if server has a meaningful rating that
                        // differs from the local value. -1 = no rating tag on
                        // server, 0 = unrated locally; treat both as "unrated".
                        val localEffective = if (info.rating <= 0) -1f else info.rating
                        val serverEffective = if (serverRating <= 0) -1f else serverRating
                        if (serverEffective != localEffective) {
                            info.rating = if (serverRating < 0) 0f else serverRating
                            ServiceRegistry.dataModule.downloadDbRepository.putDownloadInfo(info)
                        }
                    } catch (e: Exception) {
                        // Skip this archive on error, continue with next
                    }
                }

                // Notify UI on main thread
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    eventBus.forEachListener { it.onChange() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Background rating sync failed", e)
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
    fun containDownloadInfo(gid: Long): Boolean = repo.containDownloadInfo(gid)
    fun getDownloadInfo(gid: Long): DownloadInfo? = repo.getDownloadInfo(gid)
    fun getDownloadState(gid: Long): Int = repo.getDownloadState(gid)
    fun getLabelCount(label: String?): Long = repo.getLabelCount(label)
    fun getNoneDownloadInfo(gid: Long): DownloadInfo? = scheduler.getNoneDownloadInfo(gid)
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

    val downloadInfoList: List<GalleryInfo> get() { repo.assertMainThread(); return ArrayList(repo.allInfoList) }

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

    fun startDownload(galleryInfo: GalleryInfo, label: String?) {
        repo.assertMainThread()
        for (active in scheduler.activeTasks) { if (active.gid == galleryInfo.gid) return }
        if (galleryInfo is DownloadInfo) {
            val uri = galleryInfo.archiveUri
            if (uri != null && uri.startsWith("content://")) return
        }
        val existing = repo.getDownloadInfo(galleryInfo.gid)
        if (existing != null) {
            if (existing.state != DownloadInfo.STATE_WAIT) {
                existing.state = DownloadInfo.STATE_WAIT
                scheduler.waitList.add(existing)
                repo.persistInfo(existing)
                val list = repo.getInfoListForLabel(existing.label)
                if (list != null) eventBus.forEachListener { it.onUpdate(existing, list, scheduler.waitList) }
                scheduler.ensureDownload()
            }
        } else {
            val info = DownloadInfo(galleryInfo).apply { this.label = label; state = DownloadInfo.STATE_WAIT; time = System.currentTimeMillis() }
            val list = repo.getInfoListForLabel(info.label) ?: run { Log.e(TAG, "Can't find download info list with label: $label"); return }
            list.add(0, info)
            repo.allInfoList.add(0, info)
            repo.allInfoMap[galleryInfo.gid] = info
            scheduler.waitList.add(info)
            repo.persistInfo(info)
            eventBus.forEachListener { it.onAdd(info, list, list.size - 1) }
            scheduler.ensureDownload()
            repo.persistHistory(info)
        }
    }

    fun startRangeDownload(gidList: LongList) {
        repo.assertMainThread()
        var update = false
        val downloadOrder = DownloadSettings.getDownloadOrder()
        if (downloadOrder) {
            for (i in 0 until gidList.size()) {
                val info = repo.allInfoMap[gidList.get(i)] ?: continue
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED || info.state == DownloadInfo.STATE_FINISH) {
                    update = true; info.state = DownloadInfo.STATE_WAIT; scheduler.waitList.add(info); repo.persistInfo(info)
                }
            }
        } else {
            var i = gidList.size()
            while (i > 0) {
                i--
                val info = repo.allInfoMap[gidList.get(i)] ?: continue
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED || info.state == DownloadInfo.STATE_FINISH) {
                    update = true; info.state = DownloadInfo.STATE_WAIT; scheduler.waitList.add(info); repo.persistInfo(info)
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
            if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                update = true; info.state = DownloadInfo.STATE_WAIT
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

    fun addDownload(galleryInfo: GalleryInfo, label: String?, state: Int) {
        repo.assertMainThread()
        val result = repo.addSingleDownload(galleryInfo, label, state) ?: return
        eventBus.forEachListener { it.onAdd(result.first, result.second, result.second.size - 1) }
    }

    fun addDownload(galleryInfo: GalleryInfo, label: String?) { addDownload(galleryInfo, label, DownloadInfo.STATE_NONE) }

    fun addDownloadInfo(galleryInfo: GalleryInfo, label: String?) {
        repo.assertMainThread()
        repo.addInfoOnly(galleryInfo, label)
    }

    // ── Stop / Delete ─────────────────────────────────────────

    fun stopDownload(gid: Long) {
        repo.assertMainThread()
        val info = scheduler.stopDownload(gid) ?: return
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

    fun stopRangeDownload(gidList: LongList) {
        repo.assertMainThread()
        scheduler.stopRangeDownload(gidList)
        eventBus.forEachListener { it.onUpdateAll() }
        scheduler.ensureDownload()
    }

    fun stopAllDownload() {
        repo.assertMainThread()
        scheduler.stopAllDownload()
        eventBus.forEachListener { it.onUpdateAll() }
    }

    fun deleteDownload(gid: Long) {
        repo.assertMainThread()
        scheduler.stopDownload(gid)
        val result = repo.deleteInfo(gid) ?: return
        val (info, list, index) = result
        if (index >= 0) eventBus.forEachListener { it.onRemove(info, list, index) }
        scheduler.ensureDownload()
    }

    fun deleteRangeDownload(gidList: LongList) {
        repo.assertMainThread()
        scheduler.stopRangeDownload(gidList)
        val gidSet = HashSet<Long>(gidList.size())
        for (i in 0 until gidList.size()) gidSet.add(gidList.get(i))
        repo.deleteInfoRange(gidSet)
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
                val gi = GalleryInfo()
                for (di in list) {
                    gi.gid = di.gid; gi.token = di.token; gi.title = di.title; gi.thumb = di.thumb
                    gi.category = di.category; gi.posted = di.posted; gi.uploader = di.uploader; gi.rating = di.rating
                    val dir = SpiderDen.getGalleryDownloadDir(gi) ?: continue
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
