package com.lanraragi.reader.gallery

import android.content.Context
import com.lanraragi.framework.lib.yorozuya.StringUtils
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.spider.SpiderDen
import java.io.File

/**
 * Where an archive's pages live on local disk — the one answer both the
 * standalone reader routing ([com.lanraragi.reader.ui.GalleryOpenHelper])
 * and the tank member routing ([TankMemberRouting]) use, so the two session
 * kinds can never disagree about whether a copy is local, complete, or
 * merely pending.
 *
 * Injectable (the companion is the production implementation) so routing
 * decisions can be unit-tested without Room or the download root.
 */
interface DownloadDirResolver {

    /**
     * The local download directory of [archive], or null if it isn't
     * readable offline. A non-null result is an existing `file://`
     * directory that actually contains page images, so callers can route
     * straight to `ACTION_DIR`.
     *
     * Resolution:
     *  1. Primary — [SpiderDen.findGalleryDownloadDir] maps arcid → the DB
     *     `dirname` under the recorded root. Directories are title-named
     *     (DownloadDirNaming), so the persisted pointer is the only
     *     arcid → directory link; there is no prefix to scan for.
     *  2. Legacy — the pre-W34 app-private, title-named folder.
     */
    suspend fun localDownloadDir(context: Context, archive: Archive): File?

    /**
     * The download directory of an archive that is in the download list but
     * has no page on disk yet, or null when the archive is not being
     * downloaded or its root is not a plain `file://` tree (SAF roots stay
     * on the streaming path, matching [localDownloadDir]). The directory
     * may not exist yet; the hybrid page store creates it.
     */
    suspend fun pendingDownloadDir(archive: Archive): File?

    /**
     * A local copy is complete when it holds at least [expectedPages] page
     * images. An unknown server pagecount (<= 0) is treated as complete —
     * we have no basis to second-guess the directory.
     */
    fun isLocalCopyComplete(dir: File, expectedPages: Int): Boolean

    companion object : DownloadDirResolver {

        override suspend fun localDownloadDir(context: Context, archive: Archive): File? {
            // 1. Primary resolution.
            fileDirFromUni(SpiderDen.findGalleryDownloadDir(archive.arcid))
                ?.let { primary -> if (hasImageFiles(primary)) return primary }

            // 2. Legacy app-private fallback.
            val title = archive.title.takeIf { it.isNotEmpty() } ?: return null
            val baseDir = File(context.getExternalFilesDir(null), "download")
            val dirName = title.replace(LEGACY_DIR_NAME_ILLEGAL, "_").trim()
            val oldDir = File(baseDir, dirName)
            return if (oldDir.isDirectory && hasImageFiles(oldDir)) oldDir else null
        }

        override suspend fun pendingDownloadDir(archive: Archive): File? {
            // Room lookup, not DownloadManager.getDownloadInfo: this runs on an
            // IO coroutine and the in-memory repository asserts the main thread.
            val tracked = runCatching {
                ServiceRegistry.dataModule.downloadDbRepository.isDownloadTracked(archive.arcid)
            }.getOrDefault(false)
            if (!tracked) return null
            val uni = runCatching {
                // Tracked download: the reader writes pages through into this
                // directory, so it may allocate it ahead of the worker.
                SpiderDen.allocateGalleryDownloadDir(archive.arcid, archive.title)
            }.getOrNull() ?: return null
            val uri = uni.uri
            if ("file" != uri.scheme) return null
            return File(uri.path ?: return null)
        }

        override fun isLocalCopyComplete(dir: File, expectedPages: Int): Boolean {
            if (expectedPages <= 0) return true
            return countImageFiles(dir) >= expectedPages
        }

        /**
         * Whether [dir] contains at least one image file, matching against
         * the shared [GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS] whitelist so
         * this routing check recognises exactly what the reader's dir lister
         * ([DirImageFiles]) will enumerate.
         */
        fun hasImageFiles(dir: File): Boolean {
            val files = dir.listFiles() ?: return false
            return files.any(::isImageFile)
        }

        /** Count page-image files in [dir] using the same whitelist as [hasImageFiles]. */
        fun countImageFiles(dir: File): Int {
            val files = dir.listFiles() ?: return 0
            return files.count(::isImageFile)
        }

        private fun isImageFile(f: File): Boolean =
            f.isFile && StringUtils.endsWith(f.name.lowercase(), GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS)

        /**
         * Map a [UniFile] to a [File] only when it is a `file://` directory.
         * Returns null for content:// (SAF) trees — those can't be handed to
         * `ACTION_DIR`, which expects a filesystem path — and for
         * non-existent paths.
         */
        private fun fileDirFromUni(uni: UniFile?): File? {
            val uri = uni?.uri ?: return null
            if ("file" != uri.scheme) return null
            val dir = File(uri.path ?: return null)
            return if (dir.isDirectory) dir else null
        }

        private val LEGACY_DIR_NAME_ILLEGAL = "[\\/:*?\"<>|]".toRegex()
    }
}
