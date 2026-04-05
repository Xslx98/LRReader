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

package com.hippo.ehviewer

import com.hippo.ehviewer.settings.AppearanceSettings

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log

import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.dao.*
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.util.ExceptionUtils
import com.hippo.util.SqlUtils
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.ObjectUtils
import com.hippo.lib.yorozuya.collect.SparseJLArray

import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date

/**
 * Unified database access layer.
 *
 * **Dual API**: Every public method exists in two forms:
 * - `suspend fun xxxAsync(...)` — for Kotlin coroutine callers
 * - `@JvmStatic fun xxx(...)` — `runBlocking` bridge for Java callers (safe on IO threads)
 *
 * **Main-thread safety**: All `runBlocking` bridges will log a warning if called on the
 * main thread, as this blocks the UI and risks ANR.
 * Per official Kotlin docs: "runBlocking... blocks the current thread interruptibly...
 * designed to bridge regular blocking code... to be used in main functions and in tests."
 * Per Android docs: "If the main thread is blocked... it can lead to... an Application
 * Not Responding (ANR) dialog."
 * Refs:
 * - https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html
 * - https://developer.android.com/kotlin/coroutines
 */
object EhDB {

    private const val TAG = "EhDB"

    /**
     * Wraps `runBlocking` with a main-thread check. Logs a warning with the caller's
     * stack trace if invoked on the main thread, to surface potential ANR risks during
     * development.
     */
    private fun <T> blockingDb(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "runBlocking called on main thread — risk of ANR",
                IllegalStateException("EhDB blocking call on main thread"))
        }
        return runBlocking { block() }
    }

    // Constants for import progress tracking
    const val DB_LOADING = 0
    const val DB_LOAD_FINISH = 1
    const val LOADING_STATUS = "loading_status"
    const val LOADING_PROGRESS = "loading_progress"

    @JvmField
    var MAX_HISTORY_COUNT = 100

    private lateinit var sDatabase: AppDatabase
    private var sHasOldDB = false

    private class OldDBHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {}
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        companion object {
            const val DB_NAME = "data"
            const val VERSION = 6
            const val TABLE_GALLERY = "gallery"
            const val TABLE_LOCAL_FAVOURITE = "local_favourite"
            const val TABLE_TAG = "tag"
            const val TABLE_DOWNLOAD = "download"
            const val TABLE_HISTORY = "history"
        }
    }

    @JvmStatic
    fun initialize(context: Context) {
        sHasOldDB = context.getDatabasePath("data").exists()
        sDatabase = AppDatabase.getInstance(context)
        MAX_HISTORY_COUNT = AppearanceSettings.getHistoryInfoSize()
    }

    @JvmStatic
    fun needMerge(): Boolean = sHasOldDB

    @JvmStatic
    fun mergeOldDB(context: Context) {
        val oldDBHelper = OldDBHelper(context)
        val oldDB: SQLiteDatabase
        try {
            oldDB = oldDBHelper.readableDatabase
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            return
        }

        runBlocking {
            val downloadDao = sDatabase.downloadDao()
            val browsingDao = sDatabase.browsingDao()

            // Get GalleryInfo list
            val map = SparseJLArray<GalleryInfo>()
            try {
                val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_GALLERY}", null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        while (!it.isAfterLast) {
                            val gi = GalleryInfo()
                            gi.gid = it.getInt(0).toLong()
                            gi.token = it.getString(1)
                            gi.title = it.getString(2)
                            gi.posted = it.getString(3)
                            gi.category = it.getInt(4)
                            gi.thumb = it.getString(5)
                            gi.uploader = it.getString(6)
                            try {
                                gi.rating = it.getFloat(7)
                            } catch (e: Throwable) {
                                ExceptionUtils.throwIfFatal(e)
                                gi.rating = -1.0f
                            }
                            map.put(gi.gid, gi)
                            it.moveToNext()
                        }
                    }
                }
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
            }

            // Merge local favorites
            try {
                val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_LOCAL_FAVOURITE}", null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        var i = 0L
                        while (!it.isAfterLast) {
                            val gid = it.getInt(0).toLong()
                            val gi = map.get(gid) ?: run {
                                Log.e(TAG, "Can't get GalleryInfo with gid: $gid")
                                it.moveToNext()
                                return@use
                            }
                            val info = LocalFavoriteInfo(gi)
                            info.time = i
                            browsingDao.insertLocalFavorite(info)
                            it.moveToNext()
                            i++
                        }
                    }
                }
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
            }

            // Merge quick search
            try {
                val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_TAG}", null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        while (!it.isAfterLast) {
                            val quickSearch = QuickSearch()
                            val mode = it.getInt(2)
                            var search = it.getString(4)
                            val tag = it.getString(7)
                            if (mode == ListUrlBuilder.MODE_UPLOADER && search != null && search.startsWith("uploader:")) {
                                search = search.substring("uploader:".length)
                            }
                            quickSearch.time = it.getInt(0).toLong()
                            quickSearch.name = it.getString(1)
                            quickSearch.mode = mode
                            quickSearch.category = it.getInt(3)
                            quickSearch.keyword = if (mode == ListUrlBuilder.MODE_TAG) tag else search
                            quickSearch.advanceSearch = it.getInt(5)
                            quickSearch.minRating = it.getInt(6)
                            browsingDao.insertQuickSearch(quickSearch)
                            it.moveToNext()
                        }
                    }
                }
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
            }

            // Merge download info
            try {
                val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_DOWNLOAD}", null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        var i = 0L
                        while (!it.isAfterLast) {
                            val gid = it.getInt(0).toLong()
                            val gi = map.get(gid) ?: run {
                                Log.e(TAG, "Can't get GalleryInfo with gid: $gid")
                                it.moveToNext()
                                return@use
                            }
                            val info = DownloadInfo(gi)
                            var state = it.getInt(2)
                            val legacy = it.getInt(3)
                            if (state == DownloadInfo.STATE_FINISH && legacy > 0) {
                                state = DownloadInfo.STATE_FAILED
                            }
                            info.state = state
                            info.legacy = legacy
                            info.time = if (it.columnCount == 5) it.getLong(4) else i
                            downloadDao.insert(info)
                            it.moveToNext()
                            i++
                        }
                    }
                }
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
            }

            // Merge history
            try {
                val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_HISTORY}", null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        while (!it.isAfterLast) {
                            val gid = it.getInt(0).toLong()
                            val gi = map.get(gid) ?: run {
                                Log.e(TAG, "Can't get GalleryInfo with gid: $gid")
                                it.moveToNext()
                                return@use
                            }
                            val info = HistoryInfo(gi)
                            info.mode = it.getInt(1)
                            info.time = it.getLong(2)
                            browsingDao.insertHistory(info)
                            it.moveToNext()
                        }
                    }
                }
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
            }
        }

        try {
            oldDBHelper.close()
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
        }
        sHasOldDB = false
    }

    // ═══════════════════════════════════════════════════════════
    // DOWNLOADS
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getAllDownloadInfo(): List<DownloadInfo> = blockingDb { getAllDownloadInfoAsync() }

    suspend fun getAllDownloadInfoAsync(): List<DownloadInfo> {
        val dao = sDatabase.downloadDao()
        val profileId = com.hippo.ehviewer.client.lrr.LRRAuthManager.getActiveProfileId()
        val list = if (profileId > 0) dao.getDownloadInfoByServer(profileId) else dao.getAllDownloadInfo()
        for (info in list) {
            if (info.state == DownloadInfo.STATE_WAIT || info.state == DownloadInfo.STATE_DOWNLOAD) {
                info.state = DownloadInfo.STATE_NONE
            }
        }
        return list
    }

    @JvmStatic
    fun moveDownloadInfo(infos: List<DownloadInfo>, fromPosition: Int, toPosition: Int) =
        blockingDb { moveDownloadInfoAsync(infos, fromPosition, toPosition) }

    suspend fun moveDownloadInfoAsync(infos: List<DownloadInfo>, fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        sDatabase.withTransaction {
            val dao = sDatabase.downloadDao()
            val reverse = fromPosition > toPosition
            val offset = if (reverse) toPosition else fromPosition
            val limit = if (reverse) fromPosition - toPosition + 1 else toPosition - fromPosition + 1
            val list = infos.subList(offset, offset + limit)
            val step = if (reverse) 1 else -1
            val start = if (reverse) limit - 1 else 0
            val end = if (reverse) 0 else limit - 1
            val toTime = list[end].time
            var i = end
            while (if (reverse) i < start else i > start) {
                list[i].time = list[i + step].time
                i += step
            }
            list[start].time = toTime
            dao.updateAll(list)
        }
    }

    @JvmStatic
    fun putDownloadInfo(downloadInfo: DownloadInfo) = blockingDb { putDownloadInfoAsync(downloadInfo) }

    suspend fun putDownloadInfoAsync(downloadInfo: DownloadInfo) {
        sDatabase.downloadDao().insert(downloadInfo)
    }

    @JvmStatic
    fun removeDownloadInfo(gid: Long) = blockingDb { removeDownloadInfoAsync(gid) }

    suspend fun removeDownloadInfoAsync(gid: Long) {
        sDatabase.downloadDao().deleteDownloadByKey(gid)
    }

    // ═══════════════════════════════════════════════════════════
    // DOWNLOAD DIRNAME
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getDownloadDirname(gid: Long): String? = blockingDb { getDownloadDirnameAsync(gid) }

    suspend fun getDownloadDirnameAsync(gid: Long): String? {
        return sDatabase.downloadDao().loadDirname(gid)?.dirname
    }

    @JvmStatic
    fun putDownloadDirname(gid: Long, dirname: String) = blockingDb { putDownloadDirnameAsync(gid, dirname) }

    suspend fun putDownloadDirnameAsync(gid: Long, dirname: String) {
        sDatabase.withTransaction {
            val dao = sDatabase.downloadDao()
            val raw = dao.loadDirname(gid)
            if (raw != null) {
                raw.dirname = dirname
                dao.updateDirname(raw)
            } else {
                val newRaw = DownloadDirname()
                newRaw.gid = gid
                newRaw.dirname = dirname
                dao.insertDirname(newRaw)
            }
        }
    }

    @JvmStatic
    fun removeDownloadDirname(gid: Long) = blockingDb { removeDownloadDirnameAsync(gid) }

    suspend fun removeDownloadDirnameAsync(gid: Long) {
        sDatabase.downloadDao().deleteDirnameByKey(gid)
    }

    @JvmStatic
    fun updateDownloadDirname(removeGid: Long, newGid: Long, dirname: String) =
        blockingDb { updateDownloadDirnameAsync(removeGid, newGid, dirname) }

    suspend fun updateDownloadDirnameAsync(removeGid: Long, newGid: Long, dirname: String) {
        sDatabase.withTransaction {
            val dao = sDatabase.downloadDao()
            dao.deleteDirnameByKey(removeGid)
            val raw = dao.loadDirname(newGid)
            if (raw != null) {
                raw.dirname = dirname
                dao.updateDirname(raw)
            } else {
                val newRaw = DownloadDirname()
                newRaw.gid = newGid
                newRaw.dirname = dirname
                dao.insertDirname(newRaw)
            }
        }
    }

    @JvmStatic
    fun clearDownloadDirname() = blockingDb { clearDownloadDirnameAsync() }

    suspend fun clearDownloadDirnameAsync() {
        sDatabase.downloadDao().deleteAllDirnames()
    }

    // ═══════════════════════════════════════════════════════════
    // DOWNLOAD LABELS
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getAllDownloadLabelList(): List<DownloadLabel> = blockingDb { getAllDownloadLabelListAsync() }

    suspend fun getAllDownloadLabelListAsync(): List<DownloadLabel> {
        return sDatabase.downloadDao().getAllDownloadLabels()
    }

    @JvmStatic
    fun addDownloadLabel(label: String): DownloadLabel = blockingDb { addDownloadLabelAsync(label) }

    suspend fun addDownloadLabelAsync(label: String): DownloadLabel {
        val dao = sDatabase.downloadDao()
        val existing = dao.findLabelByName(label)
        if (existing != null) return existing
        val raw = DownloadLabel()
        raw.label = label
        raw.time = System.currentTimeMillis()
        raw.id = dao.insertLabel(raw)
        return raw
    }

    @JvmStatic
    fun addDownloadLabel(raw: DownloadLabel): DownloadLabel = blockingDb { addDownloadLabelAsync(raw) }

    suspend fun addDownloadLabelAsync(raw: DownloadLabel): DownloadLabel {
        raw.id = null
        val dao = sDatabase.downloadDao()
        raw.id = dao.insertLabel(raw)
        return raw
    }

    @JvmStatic
    fun updateDownloadLabel(raw: DownloadLabel) = blockingDb { updateDownloadLabelAsync(raw) }

    suspend fun updateDownloadLabelAsync(raw: DownloadLabel) {
        sDatabase.downloadDao().updateLabel(raw)
    }

    @JvmStatic
    fun moveDownloadLabel(fromPosition: Int, toPosition: Int) =
        blockingDb { moveDownloadLabelAsync(fromPosition, toPosition) }

    suspend fun moveDownloadLabelAsync(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val reverse = fromPosition > toPosition
        val offset = if (reverse) toPosition else fromPosition
        val limit = if (reverse) fromPosition - toPosition + 1 else toPosition - fromPosition + 1
        val dao = sDatabase.downloadDao()
        val list = dao.getLabelsRange(offset, limit)
        val step = if (reverse) 1 else -1
        val start = if (reverse) limit - 1 else 0
        val end = if (reverse) 0 else limit - 1
        val toTime = list[end].time
        var i = end
        while (if (reverse) i < start else i > start) {
            list[i].time = list[i + step].time
            i += step
        }
        list[start].time = toTime
        dao.updateLabels(list)
    }

    @JvmStatic
    fun removeDownloadLabel(raw: DownloadLabel) = blockingDb { removeDownloadLabelAsync(raw) }

    suspend fun removeDownloadLabelAsync(raw: DownloadLabel) {
        sDatabase.downloadDao().deleteLabel(raw)
    }

    // ═══════════════════════════════════════════════════════════
    // LOCAL FAVORITES
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getAllLocalFavorites(): List<GalleryInfo> = blockingDb { getAllLocalFavoritesAsync() }

    suspend fun getAllLocalFavoritesAsync(): List<GalleryInfo> {
        return ArrayList<GalleryInfo>(sDatabase.browsingDao().getAllLocalFavorites())
    }

    @JvmStatic
    fun searchLocalFavorites(query: String): List<GalleryInfo> = blockingDb { searchLocalFavoritesAsync(query) }

    suspend fun searchLocalFavoritesAsync(query: String): List<GalleryInfo> {
        val escapedQuery = SqlUtils.sqlEscapeString("%$query%")
        return ArrayList<GalleryInfo>(sDatabase.browsingDao().searchLocalFavorites(escapedQuery))
    }

    @JvmStatic
    fun searchLocalFavorites(query: Long): GalleryInfo? = blockingDb { searchLocalFavoritesByIdAsync(query) }

    suspend fun searchLocalFavoritesByIdAsync(query: Long): GalleryInfo? {
        return sDatabase.browsingDao().loadLocalFavorite(query)
    }

    @JvmStatic
    fun removeLocalFavorites(gid: Long) = blockingDb { removeLocalFavoritesAsync(gid) }

    suspend fun removeLocalFavoritesAsync(gid: Long) {
        sDatabase.browsingDao().deleteLocalFavoriteByKey(gid)
    }

    @JvmStatic
    fun removeLocalFavorites(gidArray: LongArray) = blockingDb { removeLocalFavoritesAsync(gidArray) }

    suspend fun removeLocalFavoritesAsync(gidArray: LongArray) {
        val dao = sDatabase.browsingDao()
        for (gid in gidArray) {
            dao.deleteLocalFavoriteByKey(gid)
        }
    }

    @JvmStatic
    fun containLocalFavorites(gid: Long): Boolean = blockingDb { containLocalFavoritesAsync(gid) }

    suspend fun containLocalFavoritesAsync(gid: Long): Boolean {
        return sDatabase.browsingDao().loadLocalFavorite(gid) != null
    }

    @JvmStatic
    fun putLocalFavorite(galleryInfo: GalleryInfo) = blockingDb { putLocalFavoriteAsync(galleryInfo) }

    suspend fun putLocalFavoriteAsync(galleryInfo: GalleryInfo) {
        val dao = sDatabase.browsingDao()
        if (dao.loadLocalFavorite(galleryInfo.gid) == null) {
            val info = if (galleryInfo is LocalFavoriteInfo) {
                galleryInfo
            } else {
                LocalFavoriteInfo(galleryInfo).also { it.time = System.currentTimeMillis() }
            }
            dao.insertLocalFavorite(info)
        }
    }

    @JvmStatic
    fun putLocalFavorites(galleryInfoList: List<GalleryInfo>) = blockingDb { putLocalFavoritesAsync(galleryInfoList) }

    suspend fun putLocalFavoritesAsync(galleryInfoList: List<GalleryInfo>) {
        for (gi in galleryInfoList) {
            putLocalFavoriteAsync(gi)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // BLACK LIST
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getAllBlackList(): List<BlackList> = blockingDb { getAllBlackListAsync() }

    suspend fun getAllBlackListAsync(): List<BlackList> = sDatabase.miscDao().getAllBlackList()

    @JvmStatic
    fun inBlackList(badgayname: String): Boolean = blockingDb { inBlackListAsync(badgayname) }

    suspend fun inBlackListAsync(badgayname: String): Boolean =
        sDatabase.miscDao().countBlackListByName(badgayname) != 0

    @JvmStatic
    fun insertBlackList(blackList: BlackList) = blockingDb { insertBlackListAsync(blackList) }

    suspend fun insertBlackListAsync(blackList: BlackList) {
        blackList.id = null
        if (blackList.badgayname == null) return
        sDatabase.miscDao().insertBlackList(blackList)
    }

    @JvmStatic
    fun updateBlackList(blackList: BlackList) = blockingDb { updateBlackListAsync(blackList) }

    suspend fun updateBlackListAsync(blackList: BlackList) {
        sDatabase.miscDao().updateBlackList(blackList)
    }

    @JvmStatic
    fun deleteBlackList(blackList: BlackList) = blockingDb { deleteBlackListAsync(blackList) }

    suspend fun deleteBlackListAsync(blackList: BlackList) {
        sDatabase.miscDao().deleteBlackList(blackList)
    }

    // ═══════════════════════════════════════════════════════════
    // GALLERY TAGS
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getAllGalleryTags(): List<GalleryTags> = blockingDb { getAllGalleryTagsAsync() }

    suspend fun getAllGalleryTagsAsync(): List<GalleryTags> = sDatabase.miscDao().getAllGalleryTags()

    @JvmStatic
    fun inGalleryTags(gid: Long): Boolean = blockingDb { inGalleryTagsAsync(gid) }

    suspend fun inGalleryTagsAsync(gid: Long): Boolean =
        sDatabase.miscDao().countGalleryTagsByGid(gid) != 0

    @JvmStatic
    fun queryGalleryTags(gid: Long): GalleryTags? = blockingDb { queryGalleryTagsAsync(gid) }

    suspend fun queryGalleryTagsAsync(gid: Long): GalleryTags? =
        sDatabase.miscDao().queryGalleryTags(gid)

    @JvmStatic
    fun insertGalleryTags(galleryTags: GalleryTags) = blockingDb { insertGalleryTagsAsync(galleryTags) }

    suspend fun insertGalleryTagsAsync(galleryTags: GalleryTags) {
        galleryTags.create_time = Date()
        galleryTags.update_time = galleryTags.create_time
        sDatabase.miscDao().insertGalleryTags(galleryTags)
    }

    @JvmStatic
    fun updateGalleryTags(galleryTags: GalleryTags) = blockingDb { updateGalleryTagsAsync(galleryTags) }

    suspend fun updateGalleryTagsAsync(galleryTags: GalleryTags) {
        galleryTags.update_time = Date()
        sDatabase.miscDao().updateGalleryTags(galleryTags)
    }

    @JvmStatic
    fun deleteGalleryTags(galleryTags: GalleryTags) = blockingDb { deleteGalleryTagsAsync(galleryTags) }

    suspend fun deleteGalleryTagsAsync(galleryTags: GalleryTags) {
        sDatabase.miscDao().deleteGalleryTags(galleryTags)
    }

    // ═══════════════════════════════════════════════════════════
    // QUICK SEARCH
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getAllQuickSearch(): List<QuickSearch> = blockingDb { getAllQuickSearchAsync() }

    suspend fun getAllQuickSearchAsync(): List<QuickSearch> =
        sDatabase.browsingDao().getAllQuickSearch()

    @JvmStatic
    fun insertQuickSearch(quickSearch: QuickSearch) = blockingDb { insertQuickSearchAsync(quickSearch) }

    suspend fun insertQuickSearchAsync(quickSearch: QuickSearch) {
        quickSearch.id = null
        if (quickSearch.time == 0L) {
            quickSearch.time = System.currentTimeMillis()
        }
        quickSearch.id = sDatabase.browsingDao().insertQuickSearch(quickSearch)
    }

    @JvmStatic
    fun insertQuickSearchList(quickSearchList: List<QuickSearch>) =
        blockingDb { insertQuickSearchListAsync(quickSearchList) }

    suspend fun insertQuickSearchListAsync(quickSearchList: List<QuickSearch>) {
        val dao = sDatabase.browsingDao()
        for (search in quickSearchList) {
            search.id = null
            search.time = System.currentTimeMillis()
            search.id = dao.insertQuickSearch(search)
        }
    }

    @JvmStatic
    fun takeOverQuickSearchList(quickSearchList: List<QuickSearch>) =
        blockingDb { takeOverQuickSearchListAsync(quickSearchList) }

    suspend fun takeOverQuickSearchListAsync(quickSearchList: List<QuickSearch>) {
        val dao = sDatabase.browsingDao()
        val allList = dao.getAllQuickSearch()
        for (newSearch in quickSearchList) {
            var insert = true
            for (exist in allList) {
                if (exist.keyword == newSearch.keyword) {
                    insert = false
                    break
                }
            }
            if (insert) {
                newSearch.id = null
                newSearch.time = System.currentTimeMillis()
                newSearch.id = dao.insertQuickSearch(newSearch)
            }
        }
    }

    @JvmStatic
    fun updateQuickSearch(quickSearch: QuickSearch) = blockingDb { updateQuickSearchAsync(quickSearch) }

    suspend fun updateQuickSearchAsync(quickSearch: QuickSearch) {
        sDatabase.browsingDao().updateQuickSearch(quickSearch)
    }

    @JvmStatic
    fun deleteQuickSearch(quickSearch: QuickSearch) = blockingDb { deleteQuickSearchAsync(quickSearch) }

    suspend fun deleteQuickSearchAsync(quickSearch: QuickSearch) {
        sDatabase.browsingDao().deleteQuickSearch(quickSearch)
    }

    @JvmStatic
    fun moveQuickSearch(fromPosition: Int, toPosition: Int) =
        blockingDb { moveQuickSearchAsync(fromPosition, toPosition) }

    suspend fun moveQuickSearchAsync(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val reverse = fromPosition > toPosition
        val offset = if (reverse) toPosition else fromPosition
        val limit = if (reverse) fromPosition - toPosition + 1 else toPosition - fromPosition + 1
        val dao = sDatabase.browsingDao()
        val list = dao.getQuickSearchRange(offset, limit)
        val step = if (reverse) 1 else -1
        val start = if (reverse) limit - 1 else 0
        val end = if (reverse) 0 else limit - 1
        val toTime = list[end].time
        var i = end
        while (if (reverse) i < start else i > start) {
            list[i].time = list[i + step].time
            i += step
        }
        list[start].time = toTime
        dao.updateQuickSearchList(list)
    }

    // ═══════════════════════════════════════════════════════════
    // HISTORY
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getHistoryLazyList(): List<HistoryInfo> = blockingDb { getHistoryLazyListAsync() }

    suspend fun getHistoryLazyListAsync(): List<HistoryInfo> {
        val profileId = com.hippo.ehviewer.client.lrr.LRRAuthManager.getActiveProfileId()
        return if (profileId > 0)
            sDatabase.browsingDao().getHistoryByServer(profileId)
        else
            sDatabase.browsingDao().getAllHistory()
    }

    @JvmStatic
    fun putHistoryInfo(galleryInfo: GalleryInfo) = blockingDb { putHistoryInfoAsync(galleryInfo) }

    suspend fun putHistoryInfoAsync(galleryInfo: GalleryInfo) {
        val dao = sDatabase.browsingDao()
        val info = HistoryInfo(galleryInfo)
        info.time = System.currentTimeMillis()
        dao.insertHistory(info)
        val maxCount = if (MAX_HISTORY_COUNT < 1) 100 else MAX_HISTORY_COUNT
        dao.trimHistoryTo(maxCount)
    }

    @JvmStatic
    fun putHistoryInfo(historyInfoList: List<HistoryInfo>) =
        blockingDb { putHistoryInfoListAsync(historyInfoList) }

    suspend fun putHistoryInfoListAsync(historyInfoList: List<HistoryInfo>) {
        val dao = sDatabase.browsingDao()
        for (info in historyInfoList) {
            dao.insertHistory(info)
        }
        dao.trimHistoryTo(MAX_HISTORY_COUNT)
    }

    @JvmStatic
    fun deleteHistoryInfo(info: HistoryInfo) = blockingDb { deleteHistoryInfoAsync(info) }

    suspend fun deleteHistoryInfoAsync(info: HistoryInfo) {
        sDatabase.browsingDao().deleteHistoryByKey(info.gid)
    }

    @JvmStatic
    fun clearHistoryInfo() = blockingDb { clearHistoryInfoAsync() }

    suspend fun clearHistoryInfoAsync() {
        sDatabase.browsingDao().deleteAllHistory()
    }

    // ═══════════════════════════════════════════════════════════
    // FILTER
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getAllFilter(): List<Filter> = blockingDb { getAllFilterAsync() }

    suspend fun getAllFilterAsync(): List<Filter> = sDatabase.browsingDao().getAllFilters()

    @JvmStatic
    fun addFilter(filter: Filter) = blockingDb { addFilterAsync(filter) }

    suspend fun addFilterAsync(filter: Filter) {
        filter.id = null
        filter.id = sDatabase.browsingDao().insertFilter(filter)
    }

    @JvmStatic
    fun deleteFilter(filter: Filter) = blockingDb { deleteFilterAsync(filter) }

    suspend fun deleteFilterAsync(filter: Filter) {
        sDatabase.browsingDao().deleteFilter(filter)
    }

    @JvmStatic
    fun triggerFilter(filter: Filter) = blockingDb { triggerFilterAsync(filter) }

    suspend fun triggerFilterAsync(filter: Filter) {
        filter.enable = filter.enable != true
        sDatabase.browsingDao().updateFilter(filter)
    }

    // ═══════════════════════════════════════════════════════════
    // BOOKMARKS
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getAllBookmarks(): List<BookmarkInfo> = blockingDb { getAllBookmarksAsync() }

    suspend fun getAllBookmarksAsync(): List<BookmarkInfo> = sDatabase.miscDao().getAllBookmarks()

    @JvmStatic
    fun insertBookmark(bookmark: BookmarkInfo) = blockingDb { insertBookmarkAsync(bookmark) }

    suspend fun insertBookmarkAsync(bookmark: BookmarkInfo) {
        sDatabase.miscDao().insertBookmark(bookmark)
    }

    @JvmStatic
    fun loadBookmark(gid: Long): BookmarkInfo? = blockingDb { loadBookmarkAsync(gid) }

    suspend fun loadBookmarkAsync(gid: Long): BookmarkInfo? = sDatabase.miscDao().loadBookmark(gid)

    @JvmStatic
    fun deleteBookmark(gid: Long) = blockingDb { deleteBookmarkAsync(gid) }

    suspend fun deleteBookmarkAsync(gid: Long) {
        sDatabase.miscDao().deleteBookmarkByKey(gid)
    }

    // ═══════════════════════════════════════════════════════════
    // SERVER PROFILES
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun getAllServerProfiles(): List<ServerProfile> = blockingDb { getAllServerProfilesAsync() }

    suspend fun getAllServerProfilesAsync(): List<ServerProfile> =
        sDatabase.miscDao().getAllServerProfiles()

    @JvmStatic
    fun getActiveProfile(): ServerProfile? = blockingDb { getActiveProfileAsync() }

    suspend fun getActiveProfileAsync(): ServerProfile? =
        sDatabase.miscDao().getActiveProfile()

    @JvmStatic
    fun findProfileByUrl(url: String): ServerProfile? = blockingDb { findProfileByUrlAsync(url) }

    suspend fun findProfileByUrlAsync(url: String): ServerProfile? =
        sDatabase.miscDao().findProfileByUrl(url)

    @JvmStatic
    fun insertServerProfile(profile: ServerProfile): Long = blockingDb { insertServerProfileAsync(profile) }

    suspend fun insertServerProfileAsync(profile: ServerProfile): Long =
        sDatabase.miscDao().insertServerProfile(profile)

    @JvmStatic
    fun updateServerProfile(profile: ServerProfile) = blockingDb { updateServerProfileAsync(profile) }

    suspend fun updateServerProfileAsync(profile: ServerProfile) {
        sDatabase.miscDao().updateServerProfile(profile)
    }

    @JvmStatic
    fun deleteServerProfile(profile: ServerProfile) = blockingDb { deleteServerProfileAsync(profile) }

    suspend fun deleteServerProfileAsync(profile: ServerProfile) {
        sDatabase.miscDao().deleteServerProfile(profile)
    }

    @JvmStatic
    fun deactivateAllProfiles() = blockingDb { deactivateAllProfilesAsync() }

    suspend fun deactivateAllProfilesAsync() {
        sDatabase.miscDao().deactivateAllProfiles()
    }

    // ═══════════════════════════════════════════════════════════
    // EXPORT / IMPORT (Raw SQLite — Room not involved)
    // ═══════════════════════════════════════════════════════════

    @JvmStatic
    fun exportDB(context: Context, file: File): Boolean {
        val dbFile = context.getDatabasePath("eh.db")
        if (dbFile == null || !dbFile.isFile) return false
        var inputStream: java.io.InputStream? = null
        var outputStream: java.io.OutputStream? = null
        try {
            inputStream = FileInputStream(dbFile)
            outputStream = FileOutputStream(file)
            IOUtils.copy(inputStream, outputStream)
            return true
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            IOUtils.closeQuietly(inputStream)
            IOUtils.closeQuietly(outputStream)
        }
        file.delete()
        return false
    }

    @JvmStatic
    fun importDB(context: Context, file: File, handler: Handler): String? {
        try {
            val db = SQLiteDatabase.openDatabase(
                file.path, null, SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )

            sendImportProgress(handler, 10)
            val manager = ServiceRegistry.dataModule.downloadManager

            runBlocking {
                val downloadDao = sDatabase.downloadDao()
                val browsingDao = sDatabase.browsingDao()
                val miscDao = sDatabase.miscDao()

                // Download labels
                try {
                    val cursor = db.rawQuery("SELECT * FROM DOWNLOAD_LABELS ORDER BY TIME ASC", null)
                    cursor?.use {
                        val labelList = mutableListOf<DownloadLabel>()
                        if (it.moveToFirst()) {
                            while (!it.isAfterLast) {
                                val label = DownloadLabel()
                                label.id = if (it.isNull(0)) null else it.getLong(0)
                                label.label = if (it.isNull(1)) null else it.getString(1)
                                label.time = it.getLong(2)
                                labelList.add(label)
                                it.moveToNext()
                            }
                        }
                        manager.addDownloadLabel(labelList)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import download labels", e)
                }

                // Downloads
                try {
                    val cursor = db.rawQuery("SELECT * FROM DOWNLOADS", null)
                    cursor?.use {
                        val downloadList = mutableListOf<DownloadInfo>()
                        if (it.moveToFirst()) {
                            while (!it.isAfterLast) {
                                val info = DownloadInfo()
                                info.gid = it.getLong(0)
                                info.token = if (it.isNull(1)) null else it.getString(1)
                                info.title = if (it.isNull(2)) null else it.getString(2)
                                info.titleJpn = if (it.isNull(3)) null else it.getString(3)
                                info.thumb = if (it.isNull(4)) null else it.getString(4)
                                info.category = it.getInt(5)
                                info.posted = if (it.isNull(6)) null else it.getString(6)
                                info.uploader = if (it.isNull(7)) null else it.getString(7)
                                info.rating = it.getFloat(8)
                                info.simpleLanguage = if (it.isNull(9)) null else it.getString(9)
                                info.state = it.getInt(10)
                                info.legacy = it.getInt(11)
                                info.time = it.getLong(12)
                                info.label = if (it.isNull(13)) null else it.getString(13)
                                if (it.columnCount > 14) {
                                    info.archiveUri = if (it.isNull(14)) null else it.getString(14)
                                }
                                downloadList.add(info)
                                it.moveToNext()
                            }
                        }
                        manager.addDownload(downloadList)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import downloads", e)
                }

                sendImportProgress(handler, 50)

                // Download dirname
                try {
                    val cursor = db.rawQuery("SELECT * FROM DOWNLOAD_DIRNAME", null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            while (!it.isAfterLast) {
                                putDownloadDirnameAsync(it.getLong(0), it.getString(1))
                                it.moveToNext()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import download dirnames", e)
                }

                sendImportProgress(handler, 90)

                // History
                try {
                    val cursor = db.rawQuery("SELECT * FROM HISTORY", null)
                    cursor?.use {
                        val historyList = mutableListOf<HistoryInfo>()
                        if (it.moveToFirst()) {
                            while (!it.isAfterLast) {
                                val info = HistoryInfo()
                                info.gid = it.getLong(0)
                                info.token = if (it.isNull(1)) null else it.getString(1)
                                info.title = if (it.isNull(2)) null else it.getString(2)
                                info.titleJpn = if (it.isNull(3)) null else it.getString(3)
                                info.thumb = if (it.isNull(4)) null else it.getString(4)
                                info.category = it.getInt(5)
                                info.posted = if (it.isNull(6)) null else it.getString(6)
                                info.uploader = if (it.isNull(7)) null else it.getString(7)
                                info.rating = it.getFloat(8)
                                info.simpleLanguage = if (it.isNull(9)) null else it.getString(9)
                                info.mode = it.getInt(10)
                                info.time = it.getLong(11)
                                historyList.add(info)
                                it.moveToNext()
                            }
                        }
                        putHistoryInfoListAsync(historyList)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import history", e)
                }

                // QuickSearch
                try {
                    val cursor = db.rawQuery("SELECT * FROM QUICK_SEARCH", null)
                    cursor?.use {
                        val currentQuickSearchList = browsingDao.getAllQuickSearch()
                        if (it.moveToFirst()) {
                            while (!it.isAfterLast) {
                                val qs = QuickSearch()
                                qs.id = if (it.isNull(0)) null else it.getLong(0)
                                qs.name = if (it.isNull(1)) null else it.getString(1)
                                qs.mode = it.getInt(2)
                                qs.category = it.getInt(3)
                                qs.keyword = if (it.isNull(4)) null else it.getString(4)
                                qs.advanceSearch = it.getInt(5)
                                qs.minRating = it.getInt(6)
                                qs.pageFrom = it.getInt(7)
                                qs.pageTo = it.getInt(8)
                                qs.time = it.getLong(9)

                                var duplicate = false
                                for (q in currentQuickSearchList) {
                                    if (ObjectUtils.equal(q.name, qs.name)) {
                                        duplicate = true
                                        break
                                    }
                                }
                                if (!duplicate) {
                                    insertQuickSearchAsync(qs)
                                }
                                it.moveToNext()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import quick search", e)
                }

                // LocalFavorites
                try {
                    val cursor = db.rawQuery("SELECT * FROM LOCAL_FAVORITES", null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            while (!it.isAfterLast) {
                                val info = LocalFavoriteInfo()
                                info.gid = it.getLong(0)
                                info.token = if (it.isNull(1)) null else it.getString(1)
                                info.title = if (it.isNull(2)) null else it.getString(2)
                                info.titleJpn = if (it.isNull(3)) null else it.getString(3)
                                info.thumb = if (it.isNull(4)) null else it.getString(4)
                                info.category = it.getInt(5)
                                info.posted = if (it.isNull(6)) null else it.getString(6)
                                info.uploader = if (it.isNull(7)) null else it.getString(7)
                                info.rating = it.getFloat(8)
                                info.simpleLanguage = if (it.isNull(9)) null else it.getString(9)
                                info.time = it.getLong(10)
                                putLocalFavoriteAsync(info)
                                it.moveToNext()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import local favorites", e)
                }

                // Filter
                try {
                    val cursor = db.rawQuery("SELECT * FROM FILTER", null)
                    cursor?.use {
                        val currentFilterList = browsingDao.getAllFilters()
                        if (it.moveToFirst()) {
                            while (!it.isAfterLast) {
                                val filter = Filter(
                                    if (it.isNull(0)) null else it.getLong(0),
                                    it.getInt(1),
                                    if (it.isNull(2)) null else it.getString(2),
                                    if (it.isNull(3)) null else it.getShort(3).toInt() != 0
                                )
                                if (!currentFilterList.contains(filter)) {
                                    addFilterAsync(filter)
                                }
                                it.moveToNext()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import filters", e)
                }

                // BlackList
                try {
                    val cursor = db.rawQuery("SELECT * FROM \"Black_List\"", null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            while (!it.isAfterLast) {
                                val black = BlackList()
                                black.id = if (it.isNull(0)) null else it.getLong(0)
                                black.badgayname = if (it.isNull(1)) null else it.getString(1)
                                black.reason = if (it.isNull(2)) null else it.getString(2)
                                black.angrywith = if (it.isNull(3)) null else it.getString(3)
                                black.add_time = if (it.isNull(4)) null else it.getString(4)
                                black.mode = if (it.isNull(5)) null else it.getInt(5)
                                insertBlackListAsync(black)
                                it.moveToNext()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import blacklist", e)
                }

                // GalleryTags
                try {
                    val cursor = db.rawQuery("SELECT * FROM \"Gallery_Tags\"", null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            while (!it.isAfterLast) {
                                val tags = GalleryTags()
                                tags.gid = it.getLong(0)
                                tags.rows = if (it.isNull(1)) null else it.getString(1)
                                tags.artist = if (it.isNull(2)) null else it.getString(2)
                                tags.cosplayer = if (it.isNull(3)) null else it.getString(3)
                                tags.character = if (it.isNull(4)) null else it.getString(4)
                                tags.female = if (it.isNull(5)) null else it.getString(5)
                                tags.group = if (it.isNull(6)) null else it.getString(6)
                                tags.language = if (it.isNull(7)) null else it.getString(7)
                                tags.male = if (it.isNull(8)) null else it.getString(8)
                                tags.misc = if (it.isNull(9)) null else it.getString(9)
                                tags.mixed = if (it.isNull(10)) null else it.getString(10)
                                tags.other = if (it.isNull(11)) null else it.getString(11)
                                tags.parody = if (it.isNull(12)) null else it.getString(12)
                                tags.reclass = if (it.isNull(13)) null else it.getString(13)
                                tags.create_time = if (it.isNull(14)) null else Date(it.getLong(14))
                                tags.update_time = if (it.isNull(15)) null else Date(it.getLong(15))
                                if (!inGalleryTagsAsync(tags.gid)) {
                                    insertGalleryTagsAsync(tags)
                                }
                                it.moveToNext()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import gallery tags", e)
                }
            }

            db.close()
            return null
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            return context.getString(R.string.cant_read_the_file)
        }
    }

    private fun sendImportProgress(handler: Handler, progress: Int) {
        val message = Message()
        val bundle = Bundle()
        bundle.putInt(LOADING_PROGRESS, progress)
        bundle.putInt(LOADING_STATUS, DB_LOADING)
        message.data = bundle
        handler.sendMessage(message)
    }
}
