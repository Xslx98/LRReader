package com.hippo.ehviewer.mapper

import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.HistoryInfo

import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.domain.TagGroup
import com.lanraragi.reader.domain.groupFlatTags

/**
 * Bridge functions between the [Archive] domain model, the Room
 * Entities (DownloadInfo / HistoryInfo) and the in-memory detail view
 * model [GalleryDetail].
 */

/**
 * Bridge: convert an [Archive] domain model to a fresh [DownloadInfo]
 * Entity (DOWNLOADS table). Sets the persistent + display fields from
 * Archive; download-specific fields (state, label, time, archiveUri,
 * legacy) keep their default values and must be set by the caller as
 * needed (e.g., DownloadManager.startDownload sets state=WAIT, time=now).
 *
 * EH-era fields (titleJpn / category / posted / uploader / gid) are
 * left at their defaults — LRR never populates them and W36-7 will
 * drop them from the schema entirely.
 */
fun Archive.toDownloadInfo(): DownloadInfo {
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
 * Bridge: convert an [Archive] domain model to a fresh [HistoryInfo]
 * Entity (HISTORY table). Sets the persistent + display fields from
 * Archive; history-specific fields (time, mode) keep their defaults
 * and the caller is expected to stamp `time = System.currentTimeMillis()`
 * before insert (HistoryRepository does so).
 */
fun Archive.toHistoryInfo(): HistoryInfo {
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
 * Convert a flattened [DownloadInfo] (no longer extends GalleryInfoEntity
 * post-W36-7) to an [Archive] domain model. Mirrors the GalleryInfo
 * conversion above for fields the flattened entity still carries; fields
 * dropped from DownloadInfo (pages, progress) get sane defaults — Archive
 * pagecount/progress are server-driven anyway.
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
 * Convert a flattened [HistoryInfo] (no longer extends GalleryInfoEntity
 * post-W36-8) to an [Archive] domain model. Same shape as the DownloadInfo
 * variant — pages/progress are server-driven and not stored on the entity.
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
        lastreadtime = time,
        summary = null,
        serverProfileId = serverProfileId,
    )
}

/**
 * Convert a [GalleryDetail] (post-W36-11 standalone) into an [Archive]
 * domain model. Used by GalleryDetailViewModel.getEffectiveArchive when
 * the live Archive isn't available but a cached GalleryDetail is.
 */
fun GalleryDetail.toArchive(): Archive = Archive(
    arcid = arcid,
    title = title ?: "",
    tags = tagGroupsToMap(tags),
    pagecount = pages,
    progress = progress,
    extension = "",
    filename = "",
    thumbnailUrl = thumb ?: "",
    rating = rating,
    isnew = false,
    lastreadtime = 0L,
    summary = null,
    serverProfileId = serverProfileId,
)

/**
 * Derive an [ArchiveDetail] from a cached [GalleryDetail] (when the original
 * LRRArchive is not available). Both sides now share the domain [TagGroup]
 * type post-M3, so tag groups pass through unchanged.
 */
fun GalleryDetail.toArchiveDetail(): ArchiveDetail = ArchiveDetail(
    archive = toArchive(),
    tagGroups = tags ?: emptyList(),
    language = language,
    size = size,
)

private fun tagGroupsToMap(groups: List<TagGroup>?): Map<String, List<String>> {
    if (groups.isNullOrEmpty()) return emptyMap()
    val map = LinkedHashMap<String, MutableList<String>>()
    for (g in groups) {
        map.getOrPut(g.namespace) { mutableListOf() }.addAll(g.tags)
    }
    return map
}
