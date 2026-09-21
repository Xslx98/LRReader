package com.lanraragi.reader.gallery

import android.util.Log
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.download.DownloadPageNaming
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The reader's side of a **hybrid** session: an archive that has a download
 * row (in progress, paused, failed or pending) is read from — and written
 * to — its download directory under the worker's naming
 * ([DownloadPageNaming]) instead of the reader cache. Pages the worker has
 * already landed are served straight from disk; pages the reader fetches
 * itself are written there so the worker's "already on disk and valid →
 * skip" branch later picks them up. Both writers use unique `.tmp` +
 * rename, so a page written by both sides just ends up with the same bytes
 * twice. Reader-written pages are never reported to the download progress
 * tracker (ADR-001 publishing channels); the worker counts them when its
 * window reaches them.
 *
 * Shared by the standalone reader ([LRRGalleryProvider]) and tank member
 * sources ([LrrTankMemberSource]) so the two never drift.
 *
 * @param downloadDir the archive's download directory (may not exist yet).
 * @param warmDir the archive's standalone reader cache dir, where the
 *   detail-screen warm-up may already have dropped `page_N` files.
 */
internal class HybridPageStore(
    val downloadDir: File,
    private val warmDir: File,
) {

    /**
     * Where 0-based page [index] lives, or null until the server page path
     * (which carries the extension) is known.
     */
    fun pageFile(index: Int, pagePath: String?): File? =
        pagePath?.let { DownloadPageNaming.pageFile(downloadDir, index, it) }

    /**
     * The download directory may not exist yet (READ tapped right after
     * DOWNLOAD) — create it the way the worker does, `.nomedia` included,
     * so page writes have a parent and the gallery stays out of the media
     * scanner even if the worker never gets there.
     */
    fun ensureDir() {
        try {
            if (!downloadDir.isDirectory && !downloadDir.mkdirs()) {
                Log.w(TAG, "Hybrid download dir could not be created: $downloadDir")
                return
            }
            val noMedia = File(downloadDir, ".nomedia")
            if (!noMedia.exists()) noMedia.createNewFile()
        } catch (e: IOException) {
            Log.w(TAG, "Hybrid download dir setup failed: ${e.message}")
        }
    }

    /**
     * The open-helper warm-up ([ReaderPageCache.preloadForDetail]) may
     * already have fetched page [index] into the reader cache before the
     * session knew it would address the download directory. Move that copy
     * into [target] instead of fetching the bytes a second time. Returns
     * true when [target] is now populated.
     */
    fun adoptWarmCachedPage(index: Int, target: File): Boolean {
        val warm = File(warmDir, "page_$index")
        if (!isPresent(warm)) return false
        if (!ReaderPageCache.validateImageFile(warm)) return false
        val parent = target.parentFile ?: return false
        val tmp = File(parent, "${target.name}.${Thread.currentThread().id}.tmp")
        return try {
            FileInputStream(warm).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, BUFFER_SIZE) }
            }
            if (tmp.renameTo(target)) {
                warm.delete()
                if (BuildConfig.DEBUG) Log.d(TAG, "Adopted warm-cached page $index into download dir")
                true
            } else {
                tmp.delete()
                false
            }
        } catch (e: IOException) {
            tmp.delete()
            Log.w(TAG, "Failed to adopt warm-cached page $index: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "HybridPageStore"
        private const val BUFFER_SIZE = 65536

        /** A page file counts as landed once it exists and is not a truncated stub. */
        fun isPresent(file: File): Boolean =
            file.exists() && file.length() > ReaderPageCache.MIN_IMAGE_SIZE
    }
}
