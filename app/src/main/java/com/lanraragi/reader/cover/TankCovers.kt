package com.lanraragi.reader.cover

import com.lanraragi.reader.client.api.LRRTankoubonApi

/** Where a tankoubon's cover image lives on the server. */
object TankCovers {

    /** Cover URL of [tankId] on [baseUrl]; a positive [cacheBust] becomes its `ts` parameter. */
    fun url(baseUrl: String, tankId: String, cacheBust: Long = 0L): String =
        LRRTankoubonApi.getTankoubonThumbnailUrl(baseUrl, tankId, cacheBust = cacheBust)
}
