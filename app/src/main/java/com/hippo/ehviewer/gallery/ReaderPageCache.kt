package com.hippo.ehviewer.gallery

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Centralized cache management for reader page images.
 *
 * Both [LRRGalleryProvider] (reading session) and detail-page preloading
 * use this object for cache paths, downloads, validation, and eviction,
 * ensuring a single source of truth for the on-disk layout.
 */
object ReaderPageCache {

    private const val TAG = "ReaderPageCache"
    private const val CACHE_PARENT = "lrr_pages"
    private const val BUFFER_SIZE = 65536 // 64KB for LAN transfers
    private const val SP_CACHE_ACCESS = "lrr_cache_access"
    const val MIN_IMAGE_SIZE = 1024L // 1KB — below this a file is likely corrupt
    const val MAX_TOTAL_CACHE_BYTES = 500L * 1024L * 1024L // 500MB total limit
    private const val DETAIL_PRELOAD_RADIUS = 1 // Pages before and after the progress page

    // ---- Path management ----

    fun getCacheDir(context: Context, arcId: String): File =
        File(context.applicationContext.cacheDir, "$CACHE_PARENT/$arcId")

    fun getCacheFile(context: Context, arcId: String, pageIndex: Int): File =
        File(getCacheDir(context, arcId), "page_$pageIndex")

    /**
     * Create the cache directory and `.nomedia` marker if they don't exist,
     * and record the access time for LRU eviction.
     *
     * @return the cache directory
     */
    fun ensureCacheDir(context: Context, arcId: String): File {
        val appContext = context.applicationContext
        val dir = getCacheDir(appContext, arcId)
        if (!dir.exists()) dir.mkdirs()

        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) {
            try { noMedia.createNewFile() } catch (_: IOException) { }
        }

        appContext.getSharedPreferences(SP_CACHE_ACCESS, Context.MODE_PRIVATE)
            .edit()
            .putLong(arcId, System.currentTimeMillis())
            .apply()

        return dir
    }

    // ---- Image validation ----

    /**
     * Validate that a file starts with a known image format magic bytes.
     * Supports JPEG, PNG, GIF, WebP, BMP, AVIF, JPEG XL.
     */
    fun validateImageFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(16)
                val read = fis.read(header)
                if (read < 4) return false

                // JPEG: FF D8 FF
                if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
                    header[2] == 0xFF.toByte()
                ) return true

                // PNG: 89 50 4E 47
                if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
                ) return true

                // GIF: 47 49 46 38
                if (header[0] == 0x47.toByte() && header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() && header[3] == 0x38.toByte()
                ) return true

                // WebP: RIFF....WEBP
                if (read >= 12 &&
                    header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
                    header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
                ) return true

                // BMP: 42 4D
                if (header[0] == 0x42.toByte() && header[1] == 0x4D.toByte()) return true

                // AVIF: ....ftypavif
                if (read >= 12 &&
                    header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() &&
                    header[8] == 'a'.code.toByte() && header[9] == 'v'.code.toByte() &&
                    header[10] == 'i'.code.toByte() && header[11] == 'f'.code.toByte()
                ) return true

                // JXL naked codestream: FF 0A
                if (header[0] == 0xFF.toByte() && header[1] == 0x0A.toByte()) return true

                // JXL ISOBMFF container: 00 00 00 0C 4A 58 4C 20 0D 0A 87 0A
                if (read >= 12 &&
                    header[0] == 0x00.toByte() && header[1] == 0x00.toByte() &&
                    header[2] == 0x00.toByte() && header[3] == 0x0C.toByte() &&
                    header[4] == 0x4A.toByte() && header[5] == 0x58.toByte() &&
                    header[6] == 0x4C.toByte() && header[7] == 0x20.toByte() &&
                    header[8] == 0x0D.toByte() && header[9] == 0x0A.toByte() &&
                    header[10] == 0x87.toByte() && header[11] == 0x0A.toByte()
                ) return true

                Log.w(
                    TAG,
                    String.format(
                        "Unknown image format: %02X %02X %02X %02X",
                        header[0], header[1], header[2], header[3]
                    )
                )
                false
            }
        } catch (e: IOException) {
            Log.d(TAG, "Validate image file", e)
            false
        }
    }

    // ---- Atomic download with validation ----

    /** Progress callback for download progress reporting. */
    fun interface ProgressCallback {
        fun onProgress(index: Int, percent: Float)
    }

    /**
     * Download a URL to [cacheFile] via atomic write (temp → fsync → rename)
     * with completeness and image-format validation.
     *
     * Thread-safe for *different* files; caller must handle same-file concurrency
     * (e.g. striped locks in [LRRGalleryProvider]).
     *
     * @throws IOException on network error, incomplete download, or invalid image
     */
    @Throws(IOException::class)
    fun downloadToFile(
        client: OkHttpClient,
        url: String,
        cacheFile: File,
        pageIndex: Int = 0,
        progressCallback: ProgressCallback? = null
    ) {
        if (cacheFile.exists() && cacheFile.length() > MIN_IMAGE_SIZE) return

        // Delete any corrupt/tiny cached file before re-downloading
        if (cacheFile.exists()) cacheFile.delete()

        val cacheDir = cacheFile.parentFile ?: throw IOException("No parent directory")
        val tmpFile = File(cacheDir, "${cacheFile.name}.${Thread.currentThread().id}.tmp")
        try {
            val request = Request.Builder().url(url).get().build()
            var contentLength: Long = -1
            var totalRead: Long = 0

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response body")
                contentLength = body.contentLength()

                body.byteStream().use { inputStream ->
                    FileOutputStream(tmpFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                            totalRead += read
                            if (progressCallback != null && contentLength > 0) {
                                progressCallback.onProgress(
                                    pageIndex,
                                    totalRead.toFloat() / contentLength
                                )
                            }
                        }
                        fos.fd.sync()
                    }
                }
            }

            // Validate download completeness
            if (contentLength > 0 && totalRead != contentLength) {
                tmpFile.delete()
                throw IOException(
                    "Incomplete download: expected $contentLength bytes, got $totalRead"
                )
            }
            if (totalRead < MIN_IMAGE_SIZE) {
                tmpFile.delete()
                throw IOException("Downloaded file too small ($totalRead bytes), likely corrupt")
            }

            // Validate image format
            if (!validateImageFile(tmpFile)) {
                tmpFile.delete()
                throw IOException("Downloaded file is not a valid image (bad magic bytes)")
            }

            // Atomic rename
            if (!tmpFile.renameTo(cacheFile)) {
                tmpFile.delete()
                if (!cacheFile.exists() || cacheFile.length() < MIN_IMAGE_SIZE) {
                    throw IOException("Failed to rename temp file")
                }
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    // ---- Cache eviction ----

    /**
     * Evict oldest archive cache directories until total size is within limit.
     * Uses SharedPreferences access time as LRU indicator.
     */
    fun cleanupOldCaches(context: Context, maxTotalBytes: Long = MAX_TOTAL_CACHE_BYTES) {
        val appContext = context.applicationContext
        val parentDir = File(appContext.cacheDir, CACHE_PARENT)
        if (!parentDir.exists() || !parentDir.isDirectory) return

        val archiveDirs = parentDir.listFiles() ?: return
        if (archiveDirs.isEmpty()) return

        var totalSize: Long = 0
        val dirList = archiveDirs.toMutableList()
        for (dir in dirList) totalSize += getDirSize(dir)

        if (totalSize <= maxTotalBytes) return

        val sp = appContext.getSharedPreferences(SP_CACHE_ACCESS, Context.MODE_PRIVATE)
        dirList.sortWith(Comparator.comparingLong { dir -> sp.getLong(dir.name, 0L) })

        val editor = sp.edit()
        for (dir in dirList) {
            if (totalSize <= maxTotalBytes) break
            val dirSize = getDirSize(dir)
            deleteDir(dir)
            editor.remove(dir.name)
            totalSize -= dirSize
            Log.d(TAG, "Evicted cache: ${dir.name} (${dirSize / 1024} KB)")
        }
        editor.apply()
    }

    private fun getDirSize(dir: File): Long {
        val files = dir.listFiles() ?: return 0
        var size: Long = 0
        for (f in files) size += f.length()
        return size
    }

    private fun deleteDir(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    // ---- Detail-page preload ----

    /**
     * Preload pages around [centerPage] from the detail page before the user
     * opens the reader. Downloads `[centerPage-1, centerPage, centerPage+1]`
     * (clamped to valid range) using the same cache directory and file naming
     * as the reader, producing immediate cache hits when the reader opens.
     *
     * @param centerPage 0-indexed reading progress page
     * @return Job that can be cancelled to abort the preload
     */
    fun preloadForDetail(
        context: Context,
        arcId: String,
        serverUrl: String,
        centerPage: Int
    ): Job = ServiceRegistry.coroutineModule.ioScope.launch {
        try {
            val appContext = context.applicationContext
            ensureCacheDir(appContext, arcId)

            val client = ServiceRegistry.networkModule.longReadClient
            val pages = LRRArchiveApi.getFileList(client, serverUrl, arcId)
            if (pages.isEmpty()) return@launch

            val pageClient = ServiceRegistry.networkModule.okHttpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val start = (centerPage - DETAIL_PRELOAD_RADIUS).coerceAtLeast(0)
            val end = (centerPage + DETAIL_PRELOAD_RADIUS).coerceAtMost(pages.size - 1)

            for (pageIndex in start..end) {
                val cacheFile = getCacheFile(appContext, arcId, pageIndex)
                if (cacheFile.exists() && cacheFile.length() > MIN_IMAGE_SIZE) continue

                try {
                    val pageUrl = serverUrl + pages[pageIndex]
                    downloadToFile(pageClient, pageUrl, cacheFile, pageIndex)
                    Log.d(TAG, "Detail preloaded page $pageIndex for $arcId")
                } catch (e: Exception) {
                    Log.d(TAG, "Detail preload page $pageIndex failed: ${e.message}")
                }
            }

            cleanupOldCaches(appContext)
        } catch (e: Exception) {
            Log.d(TAG, "Detail preload failed for $arcId: ${e.message}")
        }
    }
}
