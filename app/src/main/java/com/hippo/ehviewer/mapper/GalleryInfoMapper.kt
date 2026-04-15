package com.hippo.ehviewer.mapper

import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.lanraragi.reader.client.api.arcidToGid
import com.lanraragi.reader.domain.Archive

/**
 * Bridge functions between the [Archive] domain model and the legacy
 * [GalleryInfo] persistence layer.
 */

/**
 * Bridge: convert an [Archive] domain model to a persistence-layer [GalleryInfo].
 * Used for navigation (Parcelable IPC to detail/reader scenes) and download operations
 * that still consume GalleryInfo/GalleryInfoEntity.
 */
fun Archive.toGalleryInfo(): GalleryInfo {
    val gi = GalleryInfo()
    gi.gid = arcidToGid(arcid)
    gi.token = arcid
    gi.title = title
    gi.thumb = thumbnailUrl
    gi.rating = rating
    gi.pages = pagecount
    gi.progress = progress
    gi.simpleTags = flatTags.toTypedArray()
    gi.tgList = ArrayList(flatTags)
    gi.category = -1
    gi.serverProfileId = serverProfileId
    return gi
}

/**
 * Convert a [GalleryInfo] (or [DownloadInfo]) to an [Archive] domain model.
 * Used when the adapter/UI layer needs Archive for display.
 */
fun GalleryInfo.toArchive(): Archive {
    return Archive(
        arcid = token,
        title = title ?: "",
        tags = simpleTags?.let { tags ->
            val map = LinkedHashMap<String, MutableList<String>>()
            for (tag in tags) {
                val colonIdx = tag.indexOf(':')
                if (colonIdx > 0) {
                    val ns = tag.substring(0, colonIdx).trim()
                    val v = tag.substring(colonIdx + 1).trim()
                    map.getOrPut(ns) { mutableListOf() }.add(v)
                } else {
                    map.getOrPut("misc") { mutableListOf() }.add(tag)
                }
            }
            map
        } ?: emptyMap(),
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
}
