package com.hippo.ehviewer.event

import com.hippo.ehviewer.client.data.GalleryInfo

class GalleryActivityEvent(
    @JvmField val pagePosition: Int,
    @JvmField val galleryInfo: GalleryInfo
)
