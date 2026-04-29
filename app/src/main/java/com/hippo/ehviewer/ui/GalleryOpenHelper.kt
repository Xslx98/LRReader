package com.hippo.ehviewer.ui

import android.content.Context
import android.content.Intent
import com.hippo.ehviewer.gallery.ReaderPageCache
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.unifile.UniFile
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.domain.Archive
import java.io.File

/**
 * Shared utility for building the optimal Intent to open a gallery for reading.
 *
 * If local downloaded files exist for the given archive, opens with [GalleryActivity.ACTION_DIR]
 * (instant, offline). Otherwise falls back to [GalleryActivity.ACTION_LRR] (server streaming).
 */
object GalleryOpenHelper {

    /**
     * Build an Intent for reading the given archive, preferring local files if available.
     *
     * @param context Context
     * @param archive Archive to open
     * @return Intent ready for startActivity()
     */
    @JvmStatic
    suspend fun buildReadIntent(context: Context, archive: Archive): Intent {
        val intent = Intent(context, GalleryActivity::class.java)

        // Check if local downloaded files exist
        val downloadDir = getLocalDownloadDir(context, archive)
        if (downloadDir != null && hasImageFiles(downloadDir)) {
            // Local files available — read offline (instant)
            intent.action = GalleryActivity.ACTION_DIR
            intent.putExtra(GalleryActivity.KEY_FILENAME, downloadDir.absolutePath)
            intent.putExtra(GalleryActivity.KEY_ARCHIVE, archive)
            // Fire-and-forget Dir warmup so DirGalleryProvider.start()'s
            // consumeDecodedPage call has a chance of hitting before the
            // user sees the loading placeholder.
            UniFile.fromFile(downloadDir)?.let { uniFile ->
                ReaderPageCache.warmDir(context, archive.arcid, uniFile)
            }
        } else {
            // No local files — stream from LANraragi server
            intent.action = GalleryActivity.ACTION_LRR
            intent.putExtra(GalleryActivity.KEY_ARCHIVE, archive)
            // Fire-and-forget LRR warmup. preloadForDetail downloads the
            // bytes and decode-warms the slot. Idempotent w.r.t. an
            // earlier detail-page trigger; the slot's
            // store-replaces-and-recycles semantics handle a duplicate.
            val serverUrl = LRRAuthManager.getServerUrl()
            if (serverUrl != null) {
                val centerPage = (archive.progress - 1).coerceAtLeast(0)
                ReaderPageCache.preloadForDetail(context, archive.arcid, serverUrl, centerPage)
            }
        }

        return intent
    }

    /**
     * Get the local download directory for a gallery, if it exists.
     * Uses SpiderDen.getGalleryDownloadDir() for consistency with LRRDownloadWorker.
     */
    @JvmStatic
    suspend fun getLocalDownloadDir(context: Context, archive: Archive): File? {
        val uniDir = SpiderDen.getGalleryDownloadDir(archive.arcid, archive.title)
        if (uniDir != null) {
            val uri = uniDir.uri
            if ("file" == uri.scheme) {
                val dir = File(uri.path ?: return null)
                if (dir.isDirectory) {
                    return dir
                }
            }
        }
        // Fallback: check old app-private path for backwards compatibility
        val title = archive.title.takeIf { it.isNotEmpty() } ?: return null
        val baseDir = File(context.getExternalFilesDir(null), "download")
        val dirName = title.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
        val oldDir = File(baseDir, dirName)
        return if (oldDir.isDirectory) oldDir else null
    }

    /**
     * Check if a directory contains at least one image file.
     */
    @JvmStatic
    fun hasImageFiles(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        return files.any { f ->
            if (f.isFile) {
                val name = f.name.lowercase()
                name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") || name.endsWith(".gif") ||
                    name.endsWith(".webp") || name.endsWith(".bmp")
            } else {
                false
            }
        }
    }
}
