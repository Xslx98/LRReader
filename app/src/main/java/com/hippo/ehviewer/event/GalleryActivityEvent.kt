package com.hippo.ehviewer.event

import com.lanraragi.reader.domain.Archive

class GalleryActivityEvent(
    @JvmField val pagePosition: Int,
    @JvmField val archive: Archive,
)
