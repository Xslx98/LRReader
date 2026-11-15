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

package com.lanraragi.reader.dao.legacy

import com.lanraragi.reader.settings.AppearanceSettings

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.content.edit

import com.lanraragi.reader.archive.ListUrlBuilder
import com.lanraragi.reader.dao.*
import com.lanraragi.reader.dao.toArchiveJson
import com.lanraragi.framework.util.ExceptionUtils
import com.lanraragi.reader.domain.Archive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Unified database access layer.
 *
 * All public methods are `suspend fun` — call from a coroutine scope
 * (typically `Dispatchers.IO` or `ServiceRegistry.coroutineModule.ioScope`).
 *
 * The legacy `blockingDb()` bridge and all `@JvmStatic` annotations have been
 * removed (W3-5/W18-3). There is zero production `runBlocking` usage;
 * [com.lanraragi.reader.download.DownloadDirs.findGalleryDownloadDir] was converted
 * to a `suspend fun` in W5-3 (2026-04-11).
 */
/**
 * Minimal projection of the legacy `gallery` table used only by
 * [LegacyDb.mergeOldDB]. Replaces the `GalleryInfo` intermediate that
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

object LegacyDb {

    private const val TAG = "LegacyDb"

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
        sHasOldDB = shouldMerge(context)
        sDatabase = AppDatabase.getInstance(context)
        MAX_HISTORY_COUNT = AppearanceSettings.getHistoryInfoSize()
    }

    fun needMerge(): Boolean = sHasOldDB

    /**
     * Pref key (in the `boot_cleanup` file) set once a merge has completed.
     * Together with deleting the legacy file it makes the merge one-shot:
     * either alone stops a re-run, so a failed delete cannot bring it back.
     */
    const val PREF_MERGED = "legacy_db_merged"

    /** A legacy file is present and no earlier merge completed. */
    internal fun shouldMerge(context: Context): Boolean =
        context.getDatabasePath(OldDBHelper.DB_NAME).exists() &&
            !bootPrefs(context).getBoolean(PREF_MERGED, false)

    private fun bootPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("boot_cleanup", Context.MODE_PRIVATE)

    private fun SQLiteDatabase.hasTable(name: String): Boolean =
        rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name))
            .use { it.moveToFirst() }

    /**
     * Migrates data from the legacy SQLite database into the current Room database.
     *
     * Suspending function — must be called from a coroutine. The caller in
     * [LRReaderApplication.onCreate] already runs this on a `Dispatchers.IO`-backed
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

        // A failed step leaves the merge incomplete, so it runs again on the
        // next boot. Every write below is safe to repeat: the fills never
        // overwrite and quick searches are de-duplicated. A table the legacy
        // file lacks counts as empty.
        var complete = true
        suspend fun step(table: String, body: suspend () -> Unit) {
            try {
                if (oldDB.hasTable(table)) body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
                Log.e(TAG, "Legacy merge of $table failed", e)
                complete = false
            }
        }

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
        step(OldDBHelper.TABLE_GALLERY) {
            val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_GALLERY}", null)
            cursor.use {
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
        }

        // Merge local favorites
        step(OldDBHelper.TABLE_LOCAL_FAVOURITE) {
            val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_LOCAL_FAVOURITE}", null)
            cursor.use {
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
                        archiveLocalStateDao.fillFavoriteIfAbsent(gi.arcid, 0L, i)
                        it.moveToNext()
                        i++
                    }
                }
            }
        }

        // Merge quick search
        step(OldDBHelper.TABLE_TAG) {
            val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_TAG}", null)
            cursor.use {
                val existing = browsingDao.getAllQuickSearch()
                    .mapTo(HashSet()) { q -> Triple(q.name, q.mode, q.keyword) }
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
                        if (existing.add(Triple(quickSearch.name, quickSearch.mode, quickSearch.keyword))) {
                            browsingDao.insertQuickSearch(quickSearch)
                        }
                        it.moveToNext()
                    }
                }
            }
        }

        // Merge download info
        step(OldDBHelper.TABLE_DOWNLOAD) {
            val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_DOWNLOAD}", null)
            cursor.use {
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
                        var state = com.lanraragi.reader.dao.DownloadState.fromCode(it.getInt(2))
                        val legacy = it.getInt(3)
                        if (state == com.lanraragi.reader.dao.DownloadState.FINISH && legacy > 0) {
                            state = com.lanraragi.reader.dao.DownloadState.FAILED
                        }
                        val time = if (it.columnCount == 5) it.getLong(4) else i
                        // One download row per arcid, on any profile (ADR-003):
                        // an existing download always wins over the legacy one.
                        if (archiveLocalStateDao.loadDownloadRowByArcid(gi.arcid) == null) {
                            val archive = shellArchive(gi.arcid, gi.title, gi.thumb, gi.rating)
                            archiveLocalStateDao.insertOrIgnoreDownload(
                                arcid = gi.arcid,
                                serverProfileId = 0L,
                                archiveJson = archive.toArchiveJson(),
                                downloadState = state,
                                downloadLegacy = legacy,
                                downloadTime = time,
                                downloadLabel = null,
                                downloadArchiveUri = null,
                                downloadRootUri = null,
                            )
                            archiveLocalStateDao.fillDownloadIfAbsent(
                                arcid = gi.arcid,
                                serverProfileId = 0L,
                                downloadState = state,
                                downloadLegacy = legacy,
                                downloadTime = time,
                            )
                        }
                        it.moveToNext()
                        i++
                    }
                }
            }
        }

        // Merge history
        step(OldDBHelper.TABLE_HISTORY) {
            val cursor = oldDB.rawQuery("select * from ${OldDBHelper.TABLE_HISTORY}", null)
            cursor.use {
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
                        // Legacy history TIME is device milliseconds; the
                        // archive_json field is epoch SECONDS. The HISTORY_TIME
                        // column below keeps the milliseconds.
                        val archive = shellArchive(gi.arcid, gi.title, gi.thumb, gi.rating)
                            .copy(lastreadtime = time / 1000L)
                        val archiveJson = archive.toArchiveJson()
                        archiveLocalStateDao.insertOrIgnoreHistory(gi.arcid, 0L, archiveJson, time, mode)
                        archiveLocalStateDao.fillHistoryIfAbsent(gi.arcid, 0L, time, mode)
                        it.moveToNext()
                    }
                }
            }
        }

        try {
            oldDBHelper.close()
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
        }
        if (complete) {
            // Marker first (synchronous), then the file: a death between the
            // two leaves the marker, and a failed delete is covered by it.
            bootPrefs(context).edit(commit = true) { putBoolean(PREF_MERGED, true) }
            if (!context.deleteDatabase(OldDBHelper.DB_NAME)) {
                Log.w(TAG, "Legacy database not deleted after merge")
            }
        }
        sHasOldDB = false
    }
}
