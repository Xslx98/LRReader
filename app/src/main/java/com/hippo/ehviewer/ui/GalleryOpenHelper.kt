package com.hippo.ehviewer.ui

import android.content.Context
import android.content.Intent
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.spider.SpiderDen
import java.io.File

/**
 * Shared utility for building the optimal Intent to open a gallery for reading.
 *
 * If local downloaded files exist for the given archive, opens with [GalleryActivity.ACTION_DIR]
 * (instant, offline). Otherwise falls back to [GalleryActivity.ACTION_LRR] (server streaming).
 */
object GalleryOpenHelper {

    /**
     * Build an Intent for reading the given gallery, preferring local files if available.
     *
     * @param context    Context
     * @param galleryInfo Gallery to open
     * @return Intent ready for startActivity()
     */
    @JvmStatic
    fun buildReadIntent(context: Context, galleryInfo: GalleryInfo): Intent {
        val intent = Intent(context, GalleryActivity::class.java)

        // Check if local downloaded files exist
        val downloadDir = getLocalDownloadDir(context, galleryInfo)
        if (downloadDir != null && hasImageFiles(downloadDir)) {
            // Local files available — read offline (instant)
            intent.action = GalleryActivity.ACTION_DIR
            intent.putExtra(GalleryActivity.KEY_FILENAME, downloadDir.absolutePath)
            intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, galleryInfo)
        } else {
            // No local files — stream from LANraragi server
            intent.action = GalleryActivity.ACTION_LRR
            intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, galleryInfo)
        }

        return intent
    }

    /**
     * Get the local download directory for a gallery, if it exists.
     * Uses SpiderDen.getGalleryDownloadDir() for consistency with LRRDownloadWorker.
     */
    @JvmStatic
    fun getLocalDownloadDir(context: Context, info: GalleryInfo): File? {
        val uniDir = SpiderDen.getGalleryDownloadDir(info)
        if (uniDir != null) {
            val uri = uniDir.uri
            if ("file" == uri.scheme) {
                val dir = File(uri.path!!)
                if (dir.isDirectory) {
                    return dir
                }
            }
        }
        // Fallback: check old app-private path for backwards compatibility
        val title = info.title ?: return null
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
