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
import android.util.Log

import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.dao.*
import com.hippo.ehviewer.mapper.toArchiveJson
import com.hippo.util.ExceptionUtils
import com.hippo.lib.yorozuya.IOUtils
import com.lanraragi.reader.domain.Archive

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Unified database access layer.
 *
 * All public methods are `suspend fun` — call from a coroutine scope
 * (typically `Dispatchers.IO` or `ServiceRegistry.coroutineModule.ioScope`).
 *
 * The legacy `blockingDb()` bridge and all `@JvmStatic` annotations have been
 * removed (W3-5/W18-3). There is zero production `runBlocking` usage;
 * [com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir] was converted
 * to a `suspend fun` in W5-3 (2026-04-11).
 */
/**
 * Minimal projection of the legacy `gallery` table used only by
 * [EhDB.mergeOldDB]. Replaces the `GalleryInfo` intermediate that
 * was retired in W36-11. Holds only the columns the downstream
 * Entity inserters actually copy.
 */
private data class LegacyGallery(
    val gid: Long,
    val arcid: String,
    val title: String?,
    val thumb: String?,
    val rating: Float,
)

object EhDB {

    private const val TAG = "EhDB"

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

    fun initialize(context: Context) {
        sHasOldDB = context.getDatabasePath("data").exists()
        sDatabase = AppDatabase.getInstance(context)
        MAX_HISTORY_COUNT = AppearanceSettings.getHistoryInfoSize()
    }

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

        val browsingDao = sDatabase.browsingDao()
        val archiveLocalStateDao = sDatabase.archiveLocalStateDao()

        // Build a fresh Archive shell for an arcid using only the columns
        // the legacy gallery row carried (title / thumb / rating / arcid).
        // tags is empty — it will be filled on the next LRR detail load.
        fun shellArchive(arcid: String, title: String?, thumb: String?, rating: Float): Archive =
            Archive(
                arcid = arcid,
                title = title ?: "",
                tags = emptyMap(),
                pagecount = 0,
                progress = 0,
                extension = "",
                filename = "",
                thumbnailUrl = thumb ?: "",
                rating = rating,
                isnew = false,
                lastreadtime = 0L,
                summary = null,
                serverProfileId = 0L,
            )

        // Build a transient gid -> minimal-DTO map from the legacy gallery
        // table. The DTO holds only the columns the downstream merge blocks
        // actually copy into Room Entities (arcid / title / thumb / rating);
        // posted / category / uploader / etc. are EH-era fields the LRR
        // entities no longer carry, so we don't bother projecting them.
        val map = HashMap<Long, LegacyGallery>()
        try {
            val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_GALLERY}", null)
            cursor?.use {
                if (it.moveToFirst()) {
                    while (!it.isAfterLast) {
                        val gid = it.getInt(0).toLong()
                        val rating = try {
                            it.getFloat(7)
                        } catch (e: Throwable) {
                            ExceptionUtils.throwIfFatal(e)
                            -1.0f
                        }
                        map[gid] = LegacyGallery(
                            gid = gid,
                            arcid = it.getString(1),
                            title = it.getString(2),
                            thumb = it.getString(5),
                            rating = rating,
                        )
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
                        val gi = map[gid]
                        if (gi == null) {
                            Log.e(TAG, "Can't get legacy gallery row with gid: $gid")
                            it.moveToNext()
                            continue // skip this row, keep merging the rest of the table
                        }
                        val archive = shellArchive(gi.arcid, gi.title, gi.thumb, gi.rating)
                        val archiveJson = archive.toArchiveJson()
                        archiveLocalStateDao.insertOrIgnoreFavorite(gi.arcid, 0L, archiveJson, i)
                        archiveLocalStateDao.updateFavoriteFields(gi.arcid, 0L, archiveJson, i)
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
                        val gi = map[gid]
                        if (gi == null) {
                            Log.e(TAG, "Can't get legacy gallery row with gid: $gid")
                            it.moveToNext()
                            continue // skip this row, keep merging the rest of the table
                        }
                        var state = com.hippo.ehviewer.download.DownloadState.fromCode(it.getInt(2))
                        val legacy = it.getInt(3)
                        if (state == com.hippo.ehviewer.download.DownloadState.FINISH && legacy > 0) {
                            state = com.hippo.ehviewer.download.DownloadState.FAILED
                        }
                        val time = if (it.columnCount == 5) it.getLong(4) else i
                        val archive = shellArchive(gi.arcid, gi.title, gi.thumb, gi.rating)
                        val archiveJson = archive.toArchiveJson()
                        archiveLocalStateDao.insertOrIgnoreDownload(
                            arcid = gi.arcid,
                            serverProfileId = 0L,
                            archiveJson = archiveJson,
                            downloadState = state,
                            downloadLegacy = legacy,
                            downloadTime = time,
                            downloadLabel = null,
                            downloadArchiveUri = null,
                            downloadRootUri = null,
                        )
                        archiveLocalStateDao.updateDownloadFields(
                            arcid = gi.arcid,
                            serverProfileId = 0L,
                            archiveJson = archiveJson,
                            downloadState = state,
                            downloadLegacy = legacy,
                            downloadTime = time,
                            downloadLabel = null,
                            downloadArchiveUri = null,
                            downloadRootUri = null,
                        )
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
                        val gi = map[gid]
                        if (gi == null) {
                            Log.e(TAG, "Can't get legacy gallery row with gid: $gid")
                            it.moveToNext()
                            continue // skip this row, keep merging the rest of the table
                        }
                        val mode = it.getInt(1)
                        val time = it.getLong(2)
                        val archive = shellArchive(gi.arcid, gi.title, gi.thumb, gi.rating)
                            .copy(lastreadtime = time)
                        val archiveJson = archive.toArchiveJson()
                        archiveLocalStateDao.insertOrIgnoreHistory(gi.arcid, 0L, archiveJson, time, mode)
                        archiveLocalStateDao.updateHistoryFields(gi.arcid, 0L, archiveJson, time, mode)
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
    // EXPORT (Raw SQLite — Room not involved)
    // ═══════════════════════════════════════════════════════════

    fun exportDB(context: Context, file: File): Boolean {
        // Reject symlinks — canonical path must match absolute path
        val canonical = try { file.canonicalPath } catch (e: IOException) {
            Log.w(TAG, "Resolve canonical path for export", e)
            return false
        }
        val absolute = file.absolutePath
        if (canonical != absolute) {
            Log.e(TAG, "exportDB: symlink detected, rejected")
            return false
        }

        // Restrict target to app-scoped external directories
        val externalDir = context.getExternalFilesDir(null)?.canonicalPath
        val cacheDir = context.externalCacheDir?.canonicalPath
        if ((externalDir == null || !canonical.startsWith(externalDir)) &&
            (cacheDir == null || !canonical.startsWith(cacheDir))) {
            Log.e(TAG, "exportDB: target path outside app scope, rejected")
            return false
        }

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
            Log.e(TAG, "Failed to export DB", e)
        } finally {
            IOUtils.closeQuietly(inputStream)
            IOUtils.closeQuietly(outputStream)
        }
        file.delete()
        return false
    }

}
