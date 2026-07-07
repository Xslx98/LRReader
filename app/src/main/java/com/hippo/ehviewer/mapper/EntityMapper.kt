package com.hippo.ehviewer.mapper

import android.util.Log
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.dao.ArchiveLocalState
import com.hippo.ehviewer.dao.ArchiveLocalStateJson
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.HistoryInfo
import com.hippo.ehviewer.dao.LocalFavoriteInfo
import com.hippo.ehviewer.download.DownloadState
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.domain.TagGroup
import com.lanraragi.reader.domain.groupFlatTags

// ═══════════════════════════════════════════════════════════════════
//  Archive ↔ in-memory subsystem views (DownloadInfo / HistoryInfo /
//  LocalFavoriteInfo).
//
//  Post-L1 the three "Info" types are no longer Room entities — they
//  are mutable in-memory views the UI/Adapter/sync code reads and
//  writes. Persistence lives on `ArchiveLocalState`. These bridge
//  functions intentionally do NOT touch the database; their job is to
//  shape an Archive (or an ArchiveLocalState row) into the view
//  callers expect, and back again when the caller writes.
// ═══════════════════════════════════════════════════════════════════

/**
 * Build a degraded [ArchiveDetail] from an [Archive] for cache-first
 * rendering when the live LRR metadata fetch is in flight or has failed
 * (e.g., the source server is offline). The detail page can render
 * immediately with locally-known title / thumb / tags / pagecount
 * instead of going blank; the live fetch (if it succeeds) overwrites
 * this with richer data afterwards.
 *
 * `language` and `size` collapse to null because they are server-
 * derived and not carried on the navigation-arg [Archive]; the binder
 * already tolerates nulls there.
 */
fun Archive.toDegradedArchiveDetail(): ArchiveDetail =
    ArchiveDetail(
        archive = this,
        tagGroups = tags.map { (ns, values) -> TagGroup(ns, values) },
        language = null,
        size = null,
    )

/**
 * Build a fresh [DownloadInfo] view from an [Archive] domain model.
 * Sets the display fields; download-specific fields (state, label,
 * time, archiveUri, legacy) keep their default values and must be set
 * by the caller (e.g., `DownloadManager.startDownload` sets
 * `state = WAIT, time = now`).
 */
fun Archive.toDownloadInfoView(): DownloadInfo {
    val di = DownloadInfo()
    di.arcid = arcid
    di.title = title
    di.thumb = thumbnailUrl
    di.rating = rating
    di.simpleTags = flatTags.toTypedArray()
    di.serverProfileId = serverProfileId
    return di
}

/**
 * Build a fresh [HistoryInfo] view from an [Archive] domain model.
 * History-specific fields (`time`, `mode`) keep their defaults; the
 * caller is expected to stamp `time = System.currentTimeMillis()`
 * before insert (`HistoryRepository` does so).
 */
fun Archive.toHistoryInfoView(): HistoryInfo {
    val hi = HistoryInfo()
    hi.arcid = arcid
    hi.title = title
    hi.thumb = thumbnailUrl
    hi.rating = rating
    hi.simpleTags = flatTags.toTypedArray()
    hi.serverProfileId = serverProfileId
    return hi
}

/**
 * Recover an [Archive] from a [DownloadInfo] view. Lossy: tags
 * collapse to a flat list (the `simpleTags` field), `pagecount` /
 * `progress` / `extension` / `filename` reset to defaults — these are
 * server-driven and round-trip via the next LRR detail fetch.
 */
fun DownloadInfo.toArchive(): Archive {
    return Archive(
        arcid = this.arcid,
        title = title ?: "",
        tags = groupFlatTags(simpleTags),
        pagecount = 0,
        progress = 0,
        extension = "",
        filename = "",
        thumbnailUrl = thumb ?: "",
        rating = rating,
        isnew = false,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = serverProfileId,
    )
}

/**
 * Recover an [Archive] from a [HistoryInfo] view. Same lossy shape as
 * the DownloadInfo variant — pages / progress are server-driven and
 * not carried by the view. `lastreadtime` converts the view's
 * millisecond `time` (HISTORY_TIME column) to the epoch-seconds unit
 * the Archive field carries everywhere else (LANraragi semantics).
 */
fun HistoryInfo.toArchive(): Archive {
    return Archive(
        arcid = this.arcid,
        title = title ?: "",
        tags = groupFlatTags(simpleTags),
        pagecount = 0,
        progress = 0,
        extension = "",
        filename = "",
        thumbnailUrl = thumb ?: "",
        rating = rating,
        isnew = false,
        lastreadtime = time / 1000L,
        summary = null,
        serverProfileId = serverProfileId,
    )
}

// ═══════════════════════════════════════════════════════════════════
//  ArchiveLocalState row → in-memory views.
//
//  The repository layer reads ARCHIVE_LOCAL_STATE rows and hands
//  callers DownloadInfo / HistoryInfo / LocalFavoriteInfo via the
//  toXxxView() functions below. Each constructs an [Archive] from
//  the row's `archive_json` payload and overlays the subsystem
//  columns onto the view.
// ═══════════════════════════════════════════════════════════════════

private fun decodeArchive(arcid: String, archiveJson: String): Archive {
    return runCatching {
        ArchiveLocalStateJson.decodeFromString(Archive.serializer(), archiveJson)
    }.getOrElse { e ->
        // A single corrupt / schema-incompatible row must not throw out of a list mapper
        // and crash the scene (or blank the whole list at boot). Fall back to a minimal
        // Archive carrying just the arcid so the row renders (blank) and stays operable.
        if (BuildConfig.DEBUG) Log.w("EntityMapper", "Corrupt archive_json, using fallback", e)
        Archive(
            arcid = arcid,
            title = "",
            tags = emptyMap(),
            pagecount = 0,
            progress = 0,
            extension = "",
            filename = "",
            thumbnailUrl = "",
            rating = 0f,
            isnew = false,
            lastreadtime = 0L,
            summary = null,
            serverProfileId = 0L,
        )
    }
}

/**
 * Build a [DownloadInfo] view from an [ArchiveLocalState] row.
 * Subsystem columns set the download fields; the display payload
 * comes from `archive_json`.
 */
fun ArchiveLocalState.toDownloadInfoView(): DownloadInfo {
    val archive = decodeArchive(arcid, archiveJson)
    val info = DownloadInfo()
    info.arcid = arcid
    info.title = archive.title
    info.thumb = archive.thumbnailUrl
    info.rating = archive.rating
    // LRR API never populates simpleLanguage; column has no producer post-L1.
    // See DownloadInfo.updateInfo for the canonical explanation.
    info.simpleLanguage = null
    info.simpleTags = archive.flatTags.toTypedArray()
    info.serverProfileId = serverProfileId
    info.state = downloadState ?: DownloadState.NONE
    info.legacy = downloadLegacy
    info.time = downloadTime ?: 0L
    info.label = downloadLabel
    info.archiveUri = downloadArchiveUri
    info.downloadRootUri = downloadRootUri
    return info
}

/**
 * Build a [HistoryInfo] view from an [ArchiveLocalState] row.
 */
fun ArchiveLocalState.toHistoryInfoView(): HistoryInfo {
    val archive = decodeArchive(arcid, archiveJson)
    val info = HistoryInfo()
    info.arcid = arcid
    info.title = archive.title
    info.thumb = archive.thumbnailUrl
    info.rating = archive.rating
    // LRR API never populates simpleLanguage; column has no producer post-L1.
    // See DownloadInfo.updateInfo for the canonical explanation.
    info.simpleLanguage = null
    info.simpleTags = archive.flatTags.toTypedArray()
    info.serverProfileId = serverProfileId
    info.mode = historyMode
    info.time = historyTime ?: 0L
    return info
}

/**
 * Build a [LocalFavoriteInfo] view from an [ArchiveLocalState] row.
 */
fun ArchiveLocalState.toLocalFavoriteInfoView(): LocalFavoriteInfo {
    val archive = decodeArchive(arcid, archiveJson)
    val info = LocalFavoriteInfo()
    info.arcid = arcid
    info.title = archive.title
    info.thumb = archive.thumbnailUrl
    info.rating = archive.rating
    // LRR API never populates simpleLanguage; column has no producer post-L1.
    // See DownloadInfo.updateInfo for the canonical explanation.
    info.simpleLanguage = null
    info.simpleTags = archive.flatTags.toTypedArray()
    info.serverProfileId = serverProfileId
    info.time = favoriteTime ?: 0L
    return info
}

/**
 * Decode the `archive_json` column of an [ArchiveLocalState] row into
 * the [Archive] payload it contains.
 */
fun ArchiveLocalState.toArchive(): Archive = decodeArchive(arcid, archiveJson)

/**
 * Encode an [Archive] for storage in the `ARCHIVE_JSON` column.
 */
fun Archive.toArchiveJson(): String =
    ArchiveLocalStateJson.encodeToString(Archive.serializer(), this)
