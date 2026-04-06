/*
 * STUB (LANraragi: E-Hentai gallery provider removed)
 * LANraragi uses LRRGalleryProvider instead.
 */
package com.hippo.ehviewer.gallery

import android.content.Context
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.unifile.UniFile

/**
 * EhGalleryProvider — STUB.
 * E-Hentai SpiderQueen-based gallery provider has been removed.
 */
class EhGalleryProvider(context: Context, galleryInfo: GalleryInfo) : GalleryProvider2() {

    override fun size(): Int = 0

    override fun onRequest(index: Int) {}

    override fun onForceRequest(index: Int) {}

    override fun onCancelRequest(index: Int) {}

    override fun getError(): String = "E-Hentai provider removed"

    override fun getImageFilename(index: Int): String = ""

    override fun save(index: Int, file: UniFile): Boolean = false

    override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
}
