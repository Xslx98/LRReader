package com.hippo.ehviewer.mapper

import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.GalleryInfoUi
import com.hippo.ehviewer.dao.DownloadInfo

/**
 * Conversion functions between the three gallery-info layer types:
 *
 * - [GalleryInfo] (aka GalleryInfoEntity) — Room entity base, persistence layer
 * - [GalleryInfoUi] — UI display model, no Room annotations
 * - `LRRArchive` — API DTO (converted via its own `toGalleryInfoUi()`)
 */

/** Convert a persistence-layer [GalleryInfo] to a UI-layer [GalleryInfoUi]. */
fun GalleryInfo.toUi(): GalleryInfoUi {
    val ui = GalleryInfoUi()
    ui.gid = gid
    ui.token = token
    ui.title = title
    ui.titleJpn = titleJpn
    ui.thumb = thumb
    ui.category = category
    ui.posted = posted
    ui.uploader = uploader
    ui.rating = rating
    ui.rated = rated
    ui.simpleTags = simpleTags
    ui.pages = pages
    ui.progress = progress
    ui.thumbWidth = thumbWidth
    ui.thumbHeight = thumbHeight
    ui.spanSize = spanSize
    ui.spanIndex = spanIndex
    ui.spanGroupIndex = spanGroupIndex
    ui.tgList = tgList
    ui.simpleLanguage = simpleLanguage
    ui.favoriteSlot = favoriteSlot
    ui.favoriteName = favoriteName
    ui.serverProfileId = serverProfileId
    return ui
}

/** Convert a UI-layer [GalleryInfoUi] back to a persistence-layer [GalleryInfo]. */
fun GalleryInfoUi.toEntity(): GalleryInfo {
    val entity = GalleryInfo()
    entity.gid = gid
    entity.token = token
    entity.title = title
    entity.titleJpn = titleJpn
    entity.thumb = thumb
    entity.category = category
    entity.posted = posted
    entity.uploader = uploader
    entity.rating = rating
    entity.rated = rated
    entity.simpleTags = simpleTags
    entity.pages = pages
    entity.progress = progress
    entity.thumbWidth = thumbWidth
    entity.thumbHeight = thumbHeight
    entity.spanSize = spanSize
    entity.spanIndex = spanIndex
    entity.spanGroupIndex = spanGroupIndex
    entity.tgList = tgList
    entity.simpleLanguage = simpleLanguage
    entity.favoriteSlot = favoriteSlot
    entity.favoriteName = favoriteName
    entity.serverProfileId = serverProfileId
    return entity
}

/**
 * Convert a [DownloadInfo] to a [GalleryInfoUi], preserving all gallery display fields.
 * Download-specific fields (state, speed, etc.) are not included in the UI model.
 */
fun DownloadInfo.toGalleryInfoUi(): GalleryInfoUi {
    return (this as GalleryInfo).toUi()
}
