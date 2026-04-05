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
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.lib.image.Image
import com.hippo.lib.yorozuya.ConcurrentPool
import com.hippo.lib.yorozuya.ObjectUtils
import com.hippo.lib.yorozuya.SimpleHandler
import com.hippo.lib.yorozuya.collect.LongList
import com.hippo.lib.yorozuya.collect.SparseJLArray
import com.hippo.unifile.UniFile
import com.hippo.util.IoThreadPoolExecutor
import java.io.IOException
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class DownloadManager(private val mContext: Context) {

    // All download info list
    private val mAllInfoList: LinkedList<DownloadInfo>
    // All download info map
    private val mAllInfoMap: SparseJLArray<DownloadInfo>
    // label and info list map, without default label info list
    private val mMap: MutableMap<String?, LinkedList<DownloadInfo>>
    private val mLabelCountMap: MutableMap<String?, Long>
    // All labels without default label
    private val mLabelList: MutableList<DownloadLabel>
    // Store download info with default label
    private val mDefaultInfoList: LinkedList<DownloadInfo>
    // Store download info wait to start
    private val mWaitList: LinkedList<DownloadInfo>

    private val mSpeedReminder: DownloadSpeedTracker

    @Volatile
    private var mDownloadListener: DownloadListener? = null
    private val mDownloadInfoListeners: MutableList<DownloadInfoListener>

    private val mActiveTasks: MutableList<DownloadInfo> = CopyOnWriteArrayList()
    private val mActiveWorkers: MutableMap<DownloadInfo, LRRDownloadWorker> = ConcurrentHashMap()

    private val mNotifyTaskPool: ConcurrentPool<NotifyTask> = ConcurrentPool(5)

    init {
        // Get all labels
        val labels = EhDB.getAllDownloadLabelList().toMutableList()
        mLabelList = labels

        // Create list for each label
        val map = HashMap<String?, LinkedList<DownloadInfo>>()
        mMap = map
        for (label in labels) {
            map[label.label] = LinkedList()
        }

        // Create default for non tag
        mDefaultInfoList = LinkedList()

        // Get all info
        val allInfoList = EhDB.getAllDownloadInfo()
        mAllInfoList = LinkedList(allInfoList)

        // Create all info map
        val allInfoMap = SparseJLArray<DownloadInfo>(allInfoList.size + 10)
        mAllInfoMap = allInfoMap

        for (i in allInfoList.indices) {
            val info = allInfoList[i]

            val archiveUri = info.archiveUri
            if (archiveUri != null && archiveUri.startsWith("content://")) {
                try {
                    val uri = Uri.parse(archiveUri)
                    mContext.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Permission might already be taken or URI might be invalid
                    Log.w("DownloadManager", "Failed to restore URI permission for $archiveUri", e)
                }
            }

            // Add to all info map
            allInfoMap.put(info.gid, info)

            // Add to each label list
            var list = getInfoListForLabel(info.label)
            if (list == null) {
                // Can't find the label in label list
                list = LinkedList()
                map[info.label] = list
                if (!containLabel(info.label) && info.label != null) {
                    // Add label to DB and list
                    labels.add(EhDB.addDownloadLabel(info.label!!))
                }
            }
            list.add(info)
        }

        mLabelCountMap = HashMap()

        for ((key, value) in map) {
            mLabelCountMap[key] = value.size.toLong()
        }

        mWaitList = LinkedList()
        mDownloadInfoListeners = CopyOnWriteArrayList()
        mSpeedReminder = DownloadSpeedTracker(object : DownloadSpeedTracker.Callback {
            override fun getFirstActiveTask(): DownloadInfo? {
                return if (mActiveTasks.isEmpty()) null else mActiveTasks[0]
            }

            override fun getInfoListForLabel(label: String?): List<DownloadInfo>? {
                return this@DownloadManager.getInfoListForLabel(label)
            }

            override fun getDownloadListener(): DownloadListener? {
                return mDownloadListener
            }

            override fun getDownloadInfoListeners(): List<DownloadInfoListener> {
                return mDownloadInfoListeners
            }

            override fun getWaitList(): LinkedList<DownloadInfo> {
                return mWaitList
            }
        })
    }

    fun replaceInfo(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
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
        mAllInfoMap.put(newInfo.gid, newInfo)

        for (l in mDownloadInfoListeners) {
            l.onReplace(newInfo, oldInfo)
        }
    }

    private fun getInfoListForLabel(label: String?): LinkedList<DownloadInfo>? {
        return if (label == null) {
            mDefaultInfoList
        } else {
            mMap[label]
        }
    }

    fun containLabel(label: String?): Boolean {
        if (label == null) {
            return false
        }
        for (raw in mLabelList) {
            if (label == raw.label) {
                return true
            }
        }
        return false
    }

    fun containDownloadInfo(gid: Long): Boolean {
        return mAllInfoMap.indexOfKey(gid) >= 0
    }

    val labelList: List<DownloadLabel>
        get() = mLabelList

    fun getLabelCount(label: String?): Long {
        return try {
            if (mLabelCountMap.containsKey(label)) {
                mLabelCountMap[label] ?: 0L
            } else {
                0L
            }
        } catch (e: NullPointerException) {
            Analytics.recordException(e)
            0L
        }
    }

    val allDownloadInfoList: List<DownloadInfo>
        get() = mAllInfoList

    /**
     * Reload download data from DB for the current server profile.
     * Call this after switching servers.
     */
    fun reload() {
        // Stop any current downloads
        stopAllDownload()

        // Clear in-memory lists
        mAllInfoList.clear()
        mAllInfoMap.clear()
        mDefaultInfoList.clear()
        for ((_, value) in mMap) {
            value.clear()
        }

        // Reload from DB (filtered by current profile)
        val allInfoList = EhDB.getAllDownloadInfo()
        mAllInfoList.addAll(allInfoList)
        for (info in allInfoList) {
            mAllInfoMap.put(info.gid, info)
            var list = getInfoListForLabel(info.label)
            if (list == null) {
                list = LinkedList()
                mMap[info.label] = list
            }
            list.add(info)
        }

        // Notify listeners
        for (l in mDownloadInfoListeners) {
            l.onReload()
        }
    }

    val defaultDownloadInfoList: List<DownloadInfo>
        get() = mDefaultInfoList

    fun getLabelDownloadInfoList(label: String?): List<DownloadInfo>? {
        return mMap[label]
    }

    val downloadInfoList: List<GalleryInfo>
        get() = ArrayList(mAllInfoList)

    fun getDownloadInfo(gid: Long): DownloadInfo? {
        return mAllInfoMap.get(gid)
    }

    fun getNoneDownloadInfo(gid: Long): DownloadInfo? {
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
        return mAllInfoMap.get(gid)
    }

    fun getDownloadState(gid: Long): Int {
        val info = mAllInfoMap.get(gid)
        return info?.state ?: DownloadInfo.STATE_INVALID
    }

    fun addDownloadInfoListener(downloadInfoListener: DownloadInfoListener?) {
        mDownloadInfoListeners.add(downloadInfoListener!!)
    }

    fun removeDownloadInfoListener(downloadInfoListener: DownloadInfoListener?) {
        mDownloadInfoListeners.remove(downloadInfoListener)
    }

    fun setDownloadListener(listener: DownloadListener?) {
        mDownloadListener = listener
    }

    private fun ensureDownload() {
        val maxConcurrent = DownloadSettings.getConcurrentDownloads()
        while (mActiveTasks.size < maxConcurrent && mWaitList.isNotEmpty()) {
            val info = mWaitList.removeFirst()
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
            EhDB.putDownloadInfo(info)
            // Start speed count
            mSpeedReminder.start()
            // Notify start downloading
            mDownloadListener?.onStart(info)
            // Notify state update
            val list = getInfoListForLabel(info.label)
            if (list != null) {
                for (l in mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList)
                }
            }
            // Start the worker
            worker.start()
        }
    }

    fun startDownload(galleryInfo: GalleryInfo, label: String?) {
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
        val existing = mAllInfoMap.get(galleryInfo.gid)

        if (existing != null) { // Get it in download list
            if (existing.state != DownloadInfo.STATE_WAIT) {
                // Set state DownloadInfo.STATE_WAIT
                existing.state = DownloadInfo.STATE_WAIT
                // Add to wait list
                mWaitList.add(existing)
                // Update in DB
                EhDB.putDownloadInfo(existing)
                // Notify state update
                val list = getInfoListForLabel(existing.label)
                if (list != null) {
                    for (l in mDownloadInfoListeners) {
                        l.onUpdate(existing, list, mWaitList)
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
            list.addFirst(info)

            // Add to all download list and map
            mAllInfoList.addFirst(info)
            mAllInfoMap.put(galleryInfo.gid, info)

            // Add to wait list
            mWaitList.add(info)

            // Save to
            EhDB.putDownloadInfo(info)

            // Notify
            for (l in mDownloadInfoListeners) {
                l.onAdd(info, list, list.size - 1)
            }
            // Make sure download is running
            ensureDownload()

            // Add it to history
            EhDB.putHistoryInfo(info)
        }
    }

    fun startRangeDownload(gidList: LongList) {
        var update = false
        val downloadOrder = DownloadSettings.getDownloadOrder()
        if (downloadOrder) {
            for (i in 0 until gidList.size()) {
                val gid = gidList.get(i)
                val info = mAllInfoMap.get(gid)
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
                    EhDB.putDownloadInfo(info)
                }
            }
        } else {
            var i = gidList.size()
            while (i > 0) {
                val gid = gidList.get(i - 1)
                val info = mAllInfoMap.get(gid)
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
                    EhDB.putDownloadInfo(info)
                }
                i--
            }
        }

        if (update) {
            // Notify Listener
            for (l in mDownloadInfoListeners) {
                l.onUpdateAll()
            }
            // Ensure download
            ensureDownload()
        }
    }

    fun startAllDownload() {
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
                    EhDB.putDownloadInfo(info)
                }
            }
        } else {
            for (info in allInfoList) {
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                    update = true
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT
                    // Add to wait list
                    waitList.addFirst(info)
                    // Update in DB
                    EhDB.putDownloadInfo(info)
                }
            }
        }

        if (update) {
            // Notify Listener
            for (l in mDownloadInfoListeners) {
                l.onUpdateAll()
            }
            // Ensure download
            ensureDownload()
        }
    }

    fun addDownload(downloadInfoList: List<DownloadInfo>) {
        for (info in downloadInfoList) {
            if (containDownloadInfo(info.gid)) {
                // Contain
                continue
            }

            // Ensure download state
            if (DownloadInfo.STATE_WAIT == info.state ||
                DownloadInfo.STATE_DOWNLOAD == info.state
            ) {
                info.state = DownloadInfo.STATE_NONE
            }

            // Add to label download list
            var list = getInfoListForLabel(info.label)
            if (list == null) {
                // Can't find the label in label list
                list = LinkedList()
                mMap[info.label] = list
                if (!containLabel(info.label) && info.label != null) {
                    // Add label to DB and list
                    mLabelList.add(EhDB.addDownloadLabel(info.label!!))
                }
            }
            list.add(info)
            // Sort
            Collections.sort(list, DATE_DESC_COMPARATOR)

            // Add to all download list and map
            mAllInfoList.add(info)
            mAllInfoMap.put(info.gid, info)

            // Save to
            EhDB.putDownloadInfo(info)
        }

        // Sort all download list
        Collections.sort(mAllInfoList, DATE_DESC_COMPARATOR)

        // Notify
        Handler(Looper.getMainLooper()).post {
            for (l in mDownloadInfoListeners) {
                l.onReload()
            }
        }
    }

    fun addDownloadLabel(downloadLabelList: List<DownloadLabel>) {
        for (label in downloadLabelList) {
            val labelString = label.label
            if (!containLabel(labelString)) {
                mMap[labelString] = LinkedList()
                mLabelList.add(EhDB.addDownloadLabel(label))
            }
        }
    }

    fun addDownload(galleryInfo: GalleryInfo, label: String?, state: Int) {
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
        list.addFirst(info)

        // Add to all download list and map
        mAllInfoList.addFirst(info)
        mAllInfoMap.put(galleryInfo.gid, info)

        // Save to
        EhDB.putDownloadInfo(info)

        // Notify
        for (l in mDownloadInfoListeners) {
            l.onAdd(info, list, list.size - 1)
        }
    }

    fun addDownload(galleryInfo: GalleryInfo, label: String?) {
        addDownload(galleryInfo, label, DownloadInfo.STATE_NONE)
    }

    fun addDownloadInfo(galleryInfo: GalleryInfo, label: String?) {
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
        list.addFirst(info)

        // Save to
        EhDB.putDownloadInfo(info)
        mAllInfoMap.put(galleryInfo.gid, info)
    }

    fun stopDownload(gid: Long) {
        val info = stopDownloadInternal(gid)
        if (info != null) {
            // Update listener
            val list = getInfoListForLabel(info.label)
            if (list != null) {
                for (l in mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList)
                }
            }
            // Ensure download
            ensureDownload()
        }
    }

    fun stopCurrentDownload() {
        val info = stopCurrentDownloadInternal()
        if (info != null) {
            // Update listener
            val list = getInfoListForLabel(info.label)
            if (list != null) {
                for (l in mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList)
                }
            }
            // Ensure download
            ensureDownload()
        }
    }

    fun stopRangeDownload(gidList: LongList) {
        stopRangeDownloadInternal(gidList)

        // Update listener
        for (l in mDownloadInfoListeners) {
            l.onUpdateAll()
        }

        // Ensure download
        ensureDownload()
    }

    fun stopAllDownload() {
        // Stop all in wait list
        for (info in mWaitList) {
            info.state = DownloadInfo.STATE_NONE
            // Update in DB
            EhDB.putDownloadInfo(info)
        }
        mWaitList.clear()

        // Stop current
        stopCurrentDownloadInternal()

        // Notify mDownloadInfoListener
        for (l in mDownloadInfoListeners) {
            l.onUpdateAll()
        }
    }

    fun deleteDownload(gid: Long) {
        stopDownloadInternal(gid)
        val info = mAllInfoMap.get(gid)
        if (info != null) {
            // Remove from DB
            EhDB.removeDownloadInfo(info.gid)

            // Remove all list and map
            mAllInfoList.remove(info)
            mAllInfoMap.remove(info.gid)

            // Remove label list
            val list = getInfoListForLabel(info.label)
            if (list != null) {
                val index = list.indexOf(info)
                if (index >= 0) {
                    list.remove(info)
                    // Update listener
                    for (l in mDownloadInfoListeners) {
                        l.onRemove(info, list, index)
                    }
                }
            }

            // Ensure download
            ensureDownload()
        }
    }

    fun deleteRangeDownload(gidList: LongList) {
        stopRangeDownloadInternal(gidList)

        for (i in 0 until gidList.size()) {
            val gid = gidList.get(i)
            val info = mAllInfoMap.get(gid)
            if (info == null) {
                Log.d(TAG, "Can't get download info with gid: $gid")
                continue
            }

            // Remove from DB
            EhDB.removeDownloadInfo(info.gid)

            // Remove from all info map
            mAllInfoList.remove(info)
            mAllInfoMap.remove(info.gid)

            // Remove from label list
            val list = getInfoListForLabel(info.label)
            list?.remove(info)
        }

        // Update listener
        for (l in mDownloadInfoListeners) {
            l.onReload()
        }

        // Ensure download
        ensureDownload()
    }

    fun resetAllReadingProgress() {
        val list = LinkedList(mAllInfoList)

        IoThreadPoolExecutor.instance.execute {
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
                EhDB.putDownloadInfo(info)
                mDownloadListener?.onCancel(info)
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
                EhDB.putDownloadInfo(info)
                return info
            }
        }
        return null
    }

    // Update in DB
    // Update mDownloadListener
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
            EhDB.putDownloadInfo(info)
            mDownloadListener?.onCancel(info)
        }
        return stopped[0]
    }

    // Update in DB
    // Update mDownloadListener
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
                    EhDB.putDownloadInfo(info)
                }
            }
        }
    }

    /**
     * @param label Not allow new label
     */
    fun changeLabel(list: List<DownloadInfo>, label: String?) {
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
            dstList.add(info)
            info.label = label
            Collections.sort(dstList, DATE_DESC_COMPARATOR)

            // Save to DB
            EhDB.putDownloadInfo(info)
        }

        for (l in mDownloadInfoListeners) {
            l.onReload()
        }
    }

    fun addLabel(label: String?) {
        if (label == null || containLabel(label)) {
            return
        }

        mLabelList.add(EhDB.addDownloadLabel(label))
        mMap[label] = LinkedList()

        for (l in mDownloadInfoListeners) {
            l.onUpdateLabels()
        }
    }

    fun addLabelInSyncThread(label: String?) {
        if (label == null || containLabel(label)) {
            return
        }

        mLabelList.add(EhDB.addDownloadLabel(label))
        mMap[label] = LinkedList()
    }

    fun moveLabel(fromPosition: Int, toPosition: Int) {
        val item = mLabelList.removeAt(fromPosition)
        mLabelList.add(toPosition, item)
        EhDB.moveDownloadLabel(fromPosition, toPosition)

        for (l in mDownloadInfoListeners) {
            l.onUpdateLabels()
        }
    }

    fun renameLabel(from: String, to: String) {
        // Find in label list
        var found = false
        for (raw in mLabelList) {
            if (from == raw.label) {
                found = true
                raw.label = to
                // Update in DB
                EhDB.updateDownloadLabel(raw)
                break
            }
        }
        if (!found) {
            return
        }

        val list = mMap.remove(from) ?: return

        // Update info label
        for (info in list) {
            info.label = to
            // Update in DB
            EhDB.putDownloadInfo(info)
        }
        // Put list back with new label
        mMap[to] = list

        // Notify listener
        for (l in mDownloadInfoListeners) {
            l.onRenameLabel(from, to)
        }
    }

    fun deleteLabel(label: String) {
        // Find in label list and remove
        var found = false
        val iterator = mLabelList.iterator()
        while (iterator.hasNext()) {
            val raw = iterator.next()
            if (label == raw.label) {
                found = true
                iterator.remove()
                EhDB.removeDownloadLabel(raw)
                break
            }
        }
        if (!found) {
            return
        }

        val list = mMap.remove(label) ?: return

        // Update info label
        for (info in list) {
            info.label = null
            // Update in DB
            EhDB.putDownloadInfo(info)
            mDefaultInfoList.add(info)
        }

        // Sort
        Collections.sort(mDefaultInfoList, DATE_DESC_COMPARATOR)

        // Notify listener
        for (l in mDownloadInfoListeners) {
            l.onChange()
        }
    }

    val isIdle: Boolean
        get() = mActiveTasks.isEmpty() && mWaitList.isEmpty()

    private inner class NotifyTask : Runnable {

        private var mType = 0
        private var mTaskInfo: DownloadInfo? = null // task identity for task-specific events
        private var mPages = 0
        private var mIndex = 0
        private var mContentLength = 0L
        private var mReceivedSize = 0L
        private var mBytesRead = 0

        @Suppress("unused")
        private var mError: String? = null
        private var mFinished = 0
        private var mDownloaded = 0
        private var mTotal = 0

        fun setOnGetPagesData(taskInfo: DownloadInfo, pages: Int) {
            mType = TYPE_ON_GET_PAGES
            mTaskInfo = taskInfo
            mPages = pages
        }

        fun setOnGet509Data(index: Int) {
            mType = TYPE_ON_GET_509
            mIndex = index
        }

        fun setOnPageDownloadData(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
            mType = TYPE_ON_PAGE_DOWNLOAD
            mIndex = index
            mContentLength = contentLength
            mReceivedSize = receivedSize
            mBytesRead = bytesRead
        }

        fun setOnPageSuccessData(taskInfo: DownloadInfo, index: Int, finished: Int, downloaded: Int, total: Int) {
            mType = TYPE_ON_PAGE_SUCCESS
            mTaskInfo = taskInfo
            mIndex = index
            mFinished = finished
            mDownloaded = downloaded
            mTotal = total
        }

        fun setOnPageFailureDate(taskInfo: DownloadInfo, index: Int, error: String?, finished: Int, downloaded: Int, total: Int) {
            mType = TYPE_ON_PAGE_FAILURE
            mTaskInfo = taskInfo
            mIndex = index
            mError = error
            mFinished = finished
            mDownloaded = downloaded
            mTotal = total
        }

        fun setOnFinishDate(taskInfo: DownloadInfo, finished: Int, downloaded: Int, total: Int) {
            mType = TYPE_ON_FINISH
            mTaskInfo = taskInfo
            mFinished = finished
            mDownloaded = downloaded
            mTotal = total
        }

        override fun run() {
            when (mType) {
                TYPE_ON_GET_PAGES -> {
                    val info = mTaskInfo
                    if (info == null) {
                        Log.e(TAG, "Task info is null on onGetPages")
                    } else {
                        info.total = mPages
                        val list = getInfoListForLabel(info.label)
                        if (list != null) {
                            for (l in mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList)
                            }
                        }
                    }
                }
                TYPE_ON_GET_509 -> {
                    mDownloadListener?.onGet509()
                }
                TYPE_ON_PAGE_DOWNLOAD -> {
                    mSpeedReminder.onDownload(mIndex, mContentLength, mReceivedSize, mBytesRead)
                }
                TYPE_ON_PAGE_SUCCESS -> {
                    mSpeedReminder.onDone(mIndex)
                    val info = mTaskInfo
                    if (info == null) {
                        Log.e(TAG, "Task info is null on onPageSuccess")
                    } else {
                        info.finished = mFinished
                        info.downloaded = mDownloaded
                        info.total = mTotal
                        mDownloadListener?.onGetPage(info)
                        val list = getInfoListForLabel(info.label)
                        if (list != null) {
                            for (l in mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList)
                            }
                        }
                    }
                }
                TYPE_ON_PAGE_FAILURE -> {
                    mSpeedReminder.onDone(mIndex)
                    val info = mTaskInfo
                    if (info == null) {
                        Log.e(TAG, "Task info is null on onPageFailure")
                    } else {
                        info.finished = mFinished
                        info.downloaded = mDownloaded
                        info.total = mTotal
                        val list = getInfoListForLabel(info.label)
                        if (list != null) {
                            for (l in mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList)
                            }
                        }
                    }
                }
                TYPE_ON_FINISH -> {
                    mSpeedReminder.onFinish()
                    val info = mTaskInfo
                    if (info == null) {
                        Log.e(TAG, "Task info is null on onFinish")
                    } else {
                        mActiveTasks.remove(info)
                        mActiveWorkers.remove(info)
                        if (mActiveTasks.isEmpty()) mSpeedReminder.stop()
                        // Update state
                        info.finished = mFinished
                        info.downloaded = mDownloaded
                        info.total = mTotal
                        info.legacy = mTotal - mFinished
                        if (info.legacy == 0) {
                            info.state = DownloadInfo.STATE_FINISH
                        } else {
                            info.state = DownloadInfo.STATE_FAILED
                        }
                        // Update in DB
                        EhDB.putDownloadInfo(info)
                        // Notify
                        mDownloadListener?.onFinish(info)
                        val list = getInfoListForLabel(info.label)
                        if (list != null) {
                            for (l in mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList)
                            }
                        }
                        // Start next download
                        ensureDownload()
                    }
                }
            }

            mNotifyTaskPool.push(this)
        }
    }

    private inner class PerTaskListener(private val mInfo: DownloadInfo) : SpiderQueen.OnSpiderListener {

        private fun popTask(): NotifyTask {
            return mNotifyTaskPool.pop() ?: NotifyTask()
        }

        override fun onGetPages(pages: Int) {
            val task = popTask()
            task.setOnGetPagesData(mInfo, pages)
            SimpleHandler.getInstance().post(task)
        }

        override fun onGet509(index: Int) {
            val task = popTask()
            task.setOnGet509Data(index)
            SimpleHandler.getInstance().post(task)
        }

        override fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
            val task = popTask()
            task.setOnPageDownloadData(index, contentLength, receivedSize, bytesRead)
            SimpleHandler.getInstance().post(task)
        }

        override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) {
            val task = popTask()
            task.setOnPageSuccessData(mInfo, index, finished, downloaded, total)
            SimpleHandler.getInstance().post(task)
        }

        override fun onPageFailure(index: Int, error: String, finished: Int, downloaded: Int, total: Int) {
            val task = popTask()
            task.setOnPageFailureDate(mInfo, index, error, finished, downloaded, total)
            SimpleHandler.getInstance().post(task)
        }

        override fun onFinish(finished: Int, downloaded: Int, total: Int) {
            val task = popTask()
            task.setOnFinishDate(mInfo, finished, downloaded, total)
            SimpleHandler.getInstance().post(task)
        }

        override fun onGetImageSuccess(index: Int, image: Image) {}

        override fun onGetImageFailure(index: Int, error: String) {}
    }

    companion object {
        private val TAG = DownloadManager::class.java.simpleName

        const val DOWNLOAD_INFO_FILENAME = ".ehviewer"
        const val DOWNLOAD_INFO_HEADER = "gid,token,title,title_jpn,thumb,category,posted,uploader,rating,rated,simple_lang,simple_tags,thumb_width,thumb_height,span_size,span_index,span_group_index,favorite_slot,favorite_name,pages"

        // NotifyTask type constants (cannot be in inner class companion in Kotlin)
        private const val TYPE_ON_GET_PAGES = 0
        private const val TYPE_ON_GET_509 = 1
        private const val TYPE_ON_PAGE_DOWNLOAD = 2
        private const val TYPE_ON_PAGE_SUCCESS = 3
        private const val TYPE_ON_PAGE_FAILURE = 4
        private const val TYPE_ON_FINISH = 5

        @JvmField
        val DATE_DESC_COMPARATOR: Comparator<DownloadInfo> = Comparator { lhs, rhs ->
            val dif = lhs.time - rhs.time
            when {
                dif > 0 -> -1
                dif < 0 -> 1
                else -> 0
            }
        }
    }
}
