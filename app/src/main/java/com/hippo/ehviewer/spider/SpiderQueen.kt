/*
 * STUB (LANraragi: E-Hentai spider system removed)
 * LANraragi uses LRRGalleryProvider / LRRDownloadWorker instead.
 */

package com.hippo.ehviewer.spider

import android.content.Context
import androidx.annotation.IntDef
import androidx.annotation.UiThread
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile

/**
 * SpiderQueen -- STUB.
 * E-Hentai image downloading spider has been removed.
 * Only preserved: constants, OnSpiderListener interface, findStartPage().
 */
class SpiderQueen private constructor() : Runnable {

    fun addOnSpiderListener(listener: OnSpiderListener?) {}
    fun removeOnSpiderListener(listener: OnSpiderListener?) {}

    fun size(): Int = GalleryProvider.STATE_ERROR
    fun getError(): String = "E-Hentai spider removed"
    fun request(index: Int): Any? = null
    fun forceRequest(index: Int): Any? = null
    fun cancelRequest(index: Int) {}
    fun getStartPage(): Int = 0
    fun putStartPage(page: Int) {}

    fun save(index: Int, file: UniFile): Boolean = false
    fun save(index: Int, dir: UniFile, filename: String): UniFile? = null

    override fun run() {}

    @IntDef(MODE_READ, MODE_DOWNLOAD)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Mode

    @IntDef(STATE_NONE, STATE_DOWNLOADING, STATE_FINISHED, STATE_FAILED)
    @Retention(AnnotationRetention.SOURCE)
    annotation class State

    interface OnSpiderListener {
        fun onGetPages(pages: Int)
        fun onGet509(index: Int)
        fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int)
        fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int)
        fun onPageFailure(index: Int, error: String, finished: Int, downloaded: Int, total: Int)
        fun onFinish(finished: Int, downloaded: Int, total: Int)
        fun onGetImageSuccess(index: Int, image: Image)
        fun onGetImageFailure(index: Int, error: String)
    }

    companion object {
        const val MODE_READ = 0
        const val MODE_DOWNLOAD = 1
        const val STATE_NONE = 0
        const val STATE_DOWNLOADING = 1
        const val STATE_FINISHED = 2
        const val STATE_FAILED = 3
        const val DECODE_THREAD_NUM = 2
        const val SPIDER_INFO_FILENAME = ".ehviewer"
        const val SPIDER_INFO_BACKUP_DIR = "backupDir"

        @JvmStatic
        @UiThread
        fun obtainSpiderQueen(
            context: Context,
            galleryInfo: GalleryInfo,
            @Mode mode: Int
        ): SpiderQueen = SpiderQueen()

        @JvmStatic
        @UiThread
        fun releaseSpiderQueen(queen: SpiderQueen, @Mode mode: Int) {
            // No-op
        }

        @JvmStatic
        @UiThread
        fun findStartPage(context: Context, galleryInfo: GalleryInfo): Int = 0
    }
}
