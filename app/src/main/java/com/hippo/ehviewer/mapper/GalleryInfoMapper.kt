package com.hippo.ehviewer.mapper

import com.hippo.ehviewer.client.data.GalleryInfo
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
