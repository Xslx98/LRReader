package com.lanraragi.reader.event

import com.lanraragi.reader.domain.Archive

class GalleryActivityEvent(
    @JvmField val pagePosition: Int,
    @JvmField val archive: Archive,
)
