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
import android.os.Looper
import android.util.Log

import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.dao.*
import com.hippo.util.ExceptionUtils
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.collect.SparseJLArray

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Unified database access layer.
 *
 * **Dual API**: Every public method exists in two forms:
 * - `suspend fun xxxAsync(...)` — for Kotlin coroutine callers (preferred)
 * - `@JvmStatic fun xxx(...)` — `runBlocking` bridge for remaining Java callers (safe on IO threads)
 *
 * **Main-thread safety**: [blockingDb] hard-throws on the main thread in **debug** builds
 * (forcing offenders to be migrated to the `*Async` variants) and degrades to a logged
 * warning in **release** builds — which the R8 `-assumenosideeffects` rule then strips
 * entirely, so release behavior is silent passthrough. Kotlin callers should always use
 * the `suspend fun *Async` variants from a coroutine scope.
 *
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
     * Wraps `runBlocking` with a main-thread check.
     *
     * - **Debug builds** ([BuildConfig.DEBUG] = true): hard-throws an [IllegalStateException]
     *   with a full stack trace when called on the main thread. This is intentional: it forces
     *   the remaining `@JvmStatic` callers (e.g. [com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir],
     *   [com.hippo.ehviewer.sync.DownloadListInfosExecutor]) to migrate off the bridge during
     *   development before they ship. The inventory of remaining callers lives in
     *   `docs/blockingdb-callsites.md` (W1-2).
     * - **Release builds**: only logs `Log.w` and continues. Because the project's ProGuard rules
     *   strip `Log.v/d/i/w` via `-assumenosideeffects`, this becomes a silent passthrough in
     *   release — release-channel users do NOT crash on a borderline ANR. This trade-off is
     *   deliberate: the migration is forced in dev/CI without regressing existing users.
     */
    private fun <T> blockingDb(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val callsite = IllegalStateException(
                "EhDB.blockingDb called on main thread — migrate this caller to the suspend " +
                    "fun *Async variant (run from a coroutine scope on Dispatchers.IO)."
            )
            if (BuildConfig.DEBUG) {
                // Hard error in debug: forces the caller to be migrated.
                throw callsite
            }
            // Release: log only. R8 strips Log.w via -assumenosideeffects, so this is a no-op
            // in shipped builds. Existing users do not crash on a borderline ANR.
            Log.w(TAG, "runBlocking called on main thread — see suspend fun *Async variants", callsite)
        }
        return runBlocking { block() }
    }

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

    /**
     * Migrates data from the legacy SQLite database into the current Room database.
     *
     * Suspending function — must be called from a coroutine. The caller in
     * [EhApplication.onCreate] already runs this on a `Dispatchers.IO`-backed
     * `CoroutineScope`, so no `withContext(Dispatchers.IO)` wrap is required at
     * this level. The DAO calls below are themselves suspending and Room dispatches
     * them to its own background executor.
     */
    suspend fun mergeOldDB(context: Context) {
        val oldDBHelper = OldDBHelper(context)
        val oldDB: SQLiteDatabase
        try {
            oldDB = oldDBHelper.readableDatabase
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            return
        }

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

    /**
     * Returns a [Flow] that emits the current download list whenever the
     * DOWNLOADS table changes (insert/update/delete of persisted columns).
     *
     * Profile-aware: filters by the active server profile when one is set.
     *
     * **Important:** `@Ignore` fields (speed, downloaded, total, etc.) are NOT
     * persisted, so this Flow will NOT fire for progress-only changes.
     * Use the existing [DownloadInfoListener] callbacks for real-time progress.
     */
    @JvmStatic
    fun observeDownloads(): Flow<List<DownloadInfo>> {
        val profileId = com.hippo.ehviewer.client.lrr.LRRAuthManager.getActiveProfileId()
        return if (profileId > 0)
            sDatabase.downloadDao().observeDownloadsByServer(profileId)
        else
            sDatabase.downloadDao().observeAllDownloads()
    }

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

    suspend fun putDownloadInfoAsync(downloadInfo: DownloadInfo) {
        sDatabase.downloadDao().insert(downloadInfo)
    }

    suspend fun removeDownloadInfoAsync(gid: Long) {
        sDatabase.downloadDao().deleteDownloadByKey(gid)
    }

    suspend fun putDownloadInfoBatchAsync(list: List<DownloadInfo>) {
        sDatabase.withTransaction {
            sDatabase.downloadDao().insertAll(list)
        }
    }

    suspend fun removeDownloadInfoBatchAsync(gids: List<Long>) {
        sDatabase.withTransaction {
            sDatabase.downloadDao().deleteByGids(gids)
        }
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

    suspend fun removeDownloadDirnameAsync(gid: Long) {
        sDatabase.downloadDao().deleteDirnameByKey(gid)
    }

    suspend fun clearDownloadDirnameAsync() {
        sDatabase.downloadDao().deleteAllDirnames()
    }

    // ═══════════════════════════════════════════════════════════
    // DOWNLOAD LABELS
    // ═══════════════════════════════════════════════════════════

    suspend fun getAllDownloadLabelListAsync(): List<DownloadLabel> {
        return sDatabase.downloadDao().getAllDownloadLabels()
    }

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

    suspend fun addDownloadLabelAsync(raw: DownloadLabel): DownloadLabel {
        raw.id = null
        val dao = sDatabase.downloadDao()
        raw.id = dao.insertLabel(raw)
        return raw
    }

    suspend fun updateDownloadLabelAsync(raw: DownloadLabel) {
        sDatabase.downloadDao().updateLabel(raw)
    }

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

    suspend fun removeDownloadLabelAsync(raw: DownloadLabel) {
        sDatabase.downloadDao().deleteLabel(raw)
    }

    // ═══════════════════════════════════════════════════════════
    // LOCAL FAVORITES
    // ═══════════════════════════════════════════════════════════

    suspend fun removeLocalFavoritesAsync(gid: Long) {
        sDatabase.browsingDao().deleteLocalFavoriteByKey(gid)
    }

    suspend fun removeLocalFavoritesAsync(gidArray: LongArray) {
        val dao = sDatabase.browsingDao()
        for (gid in gidArray) {
            dao.deleteLocalFavoriteByKey(gid)
        }
    }

    suspend fun containLocalFavoritesAsync(gid: Long): Boolean {
        return sDatabase.browsingDao().loadLocalFavorite(gid) != null
    }

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

    // ═══════════════════════════════════════════════════════════
    // QUICK SEARCH
    // ═══════════════════════════════════════════════════════════

    suspend fun getAllQuickSearchAsync(): List<QuickSearch> =
        sDatabase.browsingDao().getAllQuickSearch()

    suspend fun insertQuickSearchAsync(quickSearch: QuickSearch) {
        quickSearch.id = null
        if (quickSearch.time == 0L) {
            quickSearch.time = System.currentTimeMillis()
        }
        quickSearch.id = sDatabase.browsingDao().insertQuickSearch(quickSearch)
    }

    suspend fun updateQuickSearchAsync(quickSearch: QuickSearch) {
        sDatabase.browsingDao().updateQuickSearch(quickSearch)
    }

    suspend fun deleteQuickSearchAsync(quickSearch: QuickSearch) {
        sDatabase.browsingDao().deleteQuickSearch(quickSearch)
    }

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

    suspend fun getHistoryLazyListAsync(): List<HistoryInfo> {
        val profileId = com.hippo.ehviewer.client.lrr.LRRAuthManager.getActiveProfileId()
        return if (profileId > 0)
            sDatabase.browsingDao().getHistoryByServer(profileId)
        else
            sDatabase.browsingDao().getAllHistory()
    }

    suspend fun putHistoryInfoAsync(galleryInfo: GalleryInfo) {
        val dao = sDatabase.browsingDao()
        val info = HistoryInfo(galleryInfo)
        info.time = System.currentTimeMillis()
        dao.insertHistory(info)
        val maxCount = if (MAX_HISTORY_COUNT < 1) 100 else MAX_HISTORY_COUNT
        dao.trimHistoryTo(maxCount)
    }

    suspend fun putHistoryInfoListAsync(historyInfoList: List<HistoryInfo>) {
        val dao = sDatabase.browsingDao()
        for (info in historyInfoList) {
            dao.insertHistory(info)
        }
        dao.trimHistoryTo(MAX_HISTORY_COUNT)
    }

    suspend fun deleteHistoryInfoAsync(info: HistoryInfo) {
        sDatabase.browsingDao().deleteHistoryByKey(info.gid)
    }

    suspend fun clearHistoryInfoAsync() {
        sDatabase.browsingDao().deleteAllHistory()
    }

    // ═══════════════════════════════════════════════════════════
    // SERVER PROFILES
    // ═══════════════════════════════════════════════════════════

    suspend fun getAllServerProfilesAsync(): List<ServerProfile> =
        sDatabase.miscDao().getAllServerProfiles()

    suspend fun getActiveProfileAsync(): ServerProfile? =
        sDatabase.miscDao().getActiveProfile()

    suspend fun findProfileByUrlAsync(url: String): ServerProfile? =
        sDatabase.miscDao().findProfileByUrl(url)

    suspend fun insertServerProfileAsync(profile: ServerProfile): Long =
        sDatabase.miscDao().insertServerProfile(profile)

    suspend fun updateServerProfileAsync(profile: ServerProfile) {
        sDatabase.miscDao().updateServerProfile(profile)
    }

    suspend fun deleteServerProfileAsync(profile: ServerProfile) {
        sDatabase.miscDao().deleteServerProfile(profile)
    }

    suspend fun deactivateAllProfilesAsync() {
        sDatabase.miscDao().deactivateAllProfiles()
    }

    // ═══════════════════════════════════════════════════════════
    // EXPORT (Raw SQLite — Room not involved)
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

}
