package com.hippo.ehviewer.download

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.runSuspend
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Downloads all pages of a LANraragi archive to the download directory.
 * Replaces SpiderQueen's MODE_DOWNLOAD for LANraragi galleries.
 *
 * Reports progress via [SpiderQueen.OnSpiderListener] so DownloadManager
 * can update UI without any changes to its notification pipeline.
 */
class LRRDownloadWorker(context: Context, private val info: DownloadInfo) {

    private val context: Context = context.applicationContext
    private val arcId: String = checkNotNull(info.token) { "DownloadInfo.token (arcid) must not be null" }
    private val serverUrl: String = checkNotNull(LRRAuthManager.getServerUrl()) { "Server URL must be configured" }

    var listener: SpiderQueen.OnSpiderListener? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var job: Job? = null

    @Volatile
    private var cancelled = false

    fun start() {
        cancelled = false
        job = scope.launch {
            try {
                doDownload()
            } catch (e: CancellationException) {
                // Normal cancellation, don't report
            } catch (e: Exception) {
                if (!cancelled) {
                    Log.e(TAG, "Uncaught exception in download worker", e)
                    listener?.run {
                        onPageFailure(0, "Unexpected error: ${e.message}", 0, 0, 0)
                        onFinish(0, 0, 0)
                    }
                }
            }
        }
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
    }

    private suspend fun doDownload() {
        val client = ServiceRegistry.networkModule.okHttpClient
        // Use longer timeout for page downloads (large archives may need extraction time)
        val pageClient = client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // Step 1: Extract archive to get page list
        val pagePaths: Array<String>
        try {
            pagePaths = runSuspend {
                LRRArchiveApi.getFileList(
                    ServiceRegistry.networkModule.longReadClient,
                    serverUrl,
                    arcId
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract archive", e)
            listener?.run {
                onPageFailure(0, "Extract failed: ${e.message}", 0, 0, 0)
                onFinish(0, 0, 0)
            }
            return
        }

        if (cancelled) return

        val total = pagePaths.size
        listener?.onGetPages(total)

        // Step 2: Prepare download directory
        val downloadDir = getDownloadDir()
        if (downloadDir == null) {
            listener?.run {
                onPageFailure(0, "Cannot create download directory", 0, 0, total)
                onFinish(0, 0, total)
            }
            return
        }

        // Create .nomedia
        val noMedia = File(downloadDir, ".nomedia")
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile()
            } catch (_: IOException) {
                // ignored
            }
        }

        // Step 3: Download each page
        var finished = 0
        var downloaded = 0

        for (i in 0 until total) {
            if (cancelled) {
                break
            }

            // Determine file extension from path
            val pagePath = pagePaths[i]
            val ext = getExtension(pagePath)
            val pageFile = File(downloadDir, "%04d%s".format(i + 1, ext))

            // Skip if already downloaded and valid
            if (pageFile.exists() && pageFile.length() > MIN_IMAGE_SIZE && validateImageFile(pageFile)) {
                finished++
                downloaded++
                listener?.onPageSuccess(i, finished, downloaded, total)
                continue
            }

            // Download the page with retry
            var success = false
            for (attempt in 0 until MAX_RETRY) {
                if (cancelled) break

                // On retry, delete potentially corrupt file
                if (attempt > 0) {
                    Log.w(TAG, "Retry page $i (attempt ${attempt + 1})")
                    if (pageFile.exists()) {
                        pageFile.delete()
                    }
                }

                try {
                    downloadPage(pageClient, pagePath, pageFile, i, total)
                    // Validate after download
                    if (!pageFile.exists() || pageFile.length() < MIN_IMAGE_SIZE) {
                        if (pageFile.exists()) pageFile.delete()
                        throw IOException("Downloaded file too small or missing")
                    }
                    if (!validateImageFile(pageFile)) {
                        pageFile.delete()
                        throw IOException("Downloaded file is not a valid image")
                    }
                    success = true
                    break
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to download page $i (attempt ${attempt + 1})", e)
                    if (attempt == MAX_RETRY - 1) {
                        // Final attempt failed
                        listener?.onPageFailure(i, e.message ?: "Unknown error", finished, downloaded, total)
                    }
                }
            }

            if (success) {
                finished++
                downloaded++
                listener?.onPageSuccess(i, finished, downloaded, total)
            }
        }

        // Step 4: Report finish
        listener?.onFinish(finished, downloaded, total)
    }

    @Throws(IOException::class)
    private fun downloadPage(
        client: OkHttpClient,
        pagePath: String,
        outFile: File,
        index: Int,
        total: Int
    ) {
        val pageUrl = serverUrl + pagePath
        val request = Request.Builder()
            .url(pageUrl)
            .get()
            .build()

        val tmpFile = File(outFile.parent, "${outFile.name}.${UUID.randomUUID()}.tmp")

        try {
            var contentLength = -1L
            var totalRead = 0L

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }

                val body = response.body ?: throw IOException("Empty response body")
                contentLength = body.contentLength()

                body.byteStream().use { inputStream ->
                    FileOutputStream(tmpFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            if (cancelled) {
                                tmpFile.delete()
                                throw IOException("Cancelled")
                            }
                            fos.write(buffer, 0, read)
                            totalRead += read
                            if (totalRead > MAX_PAGE_SIZE) {
                                tmpFile.delete()
                                throw IOException(
                                    "Page exceeds maximum size limit (${MAX_PAGE_SIZE / 1024 / 1024} MB)"
                                )
                            }
                            if (contentLength > 0) {
                                listener?.onPageDownload(index, contentLength, totalRead, read)
                            }
                        }
                        // Flush to disk before rename to prevent reading incomplete data
                        fos.fd.sync()
                    }
                }
            }

            // Validate download completeness
            if (contentLength > 0 && totalRead != contentLength) {
                tmpFile.delete()
                throw IOException("Incomplete download: expected $contentLength bytes, got $totalRead")
            }

            // Atomic rename
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.delete()
                if (!outFile.exists() || outFile.length() < MIN_IMAGE_SIZE) {
                    throw IOException("Failed to rename temp file")
                }
            }
        } finally {
            // Clean up tmp file on any failure
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    private suspend fun getDownloadDir(): File? {
        // Use the same download location as SpiderDen (DownloadSettings.getDownloadLocation())
        // so downloaded files are visible in the user-configured directory
        try {
            val uniDir = SpiderDen.getGalleryDownloadDir(info)
            if (uniDir != null && uniDir.ensureDir()) {
                val uri = uniDir.uri
                if ("file" == uri.scheme) {
                    return File(uri.path!!)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get gallery download dir from SpiderDen, using fallback", e)
        }
        // Fallback: app-private external files directory
        val baseDir = File(context.getExternalFilesDir(null), "download")
        if (!baseDir.exists()) baseDir.mkdirs()
        val archiveDir = File(baseDir, sanitizeFilename(info.title))
        if (!archiveDir.exists()) archiveDir.mkdirs()
        return archiveDir
    }

    companion object {
        private const val TAG = "LRRDownloadWorker"
        private const val BUFFER_SIZE = 65536          // 64KB for LAN
        private const val MIN_IMAGE_SIZE = 1024L       // 1KB minimum valid image
        private const val MAX_RETRY = 2                // Try up to 2 times per page
        private const val MAX_PAGE_SIZE = 200L * 1024 * 1024 // 200MB per page

        /**
         * Validate that a file starts with a known image format magic bytes.
         * Supports JPEG, PNG, GIF, WebP, BMP, AVIF, JPEG XL.
         */
        @JvmStatic
        fun validateImageFile(file: File): Boolean {
            if (!file.exists() || file.length() < 4) return false
            return try {
                FileInputStream(file).use { fis ->
                    val header = ByteArray(16)
                    val read = fis.read(header)
                    if (read < 4) return false

                    when {
                        // JPEG: FF D8 FF
                        header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
                            && header[2] == 0xFF.toByte() -> true
                        // PNG: 89 50 4E 47
                        header[0] == 0x89.toByte() && header[1] == 0x50.toByte()
                            && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> true
                        // GIF: 47 49 46 38
                        header[0] == 0x47.toByte() && header[1] == 0x49.toByte()
                            && header[2] == 0x46.toByte() && header[3] == 0x38.toByte() -> true
                        // WebP: RIFF....WEBP
                        read >= 12 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte()
                            && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
                            && header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte()
                            && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte() -> true
                        // BMP: 42 4D
                        header[0] == 0x42.toByte() && header[1] == 0x4D.toByte() -> true
                        // AVIF: ....ftypavif
                        read >= 12 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte()
                            && header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
                            && header[8] == 'a'.code.toByte() && header[9] == 'v'.code.toByte()
                            && header[10] == 'i'.code.toByte() && header[11] == 'f'.code.toByte() -> true
                        // JXL naked codestream: FF 0A
                        header[0] == 0xFF.toByte() && header[1] == 0x0A.toByte() -> true
                        // JXL ISOBMFF container: 00 00 00 0C 4A 58 4C 20 0D 0A 87 0A
                        read >= 12 && header[0] == 0x00.toByte() && header[1] == 0x00.toByte()
                            && header[2] == 0x00.toByte() && header[3] == 0x0C.toByte()
                            && header[4] == 0x4A.toByte() && header[5] == 0x58.toByte()
                            && header[6] == 0x4C.toByte() && header[7] == 0x20.toByte()
                            && header[8] == 0x0D.toByte() && header[9] == 0x0A.toByte()
                            && header[10] == 0x87.toByte() && header[11] == 0x0A.toByte() -> true
                        else -> {
                            Log.w(
                                TAG,
                                "Unknown image format: %02X %02X %02X %02X".format(
                                    header[0], header[1], header[2], header[3]
                                )
                            )
                            false
                        }
                    }
                }
            } catch (_: IOException) {
                false
            }
        }

        @JvmStatic
        private fun sanitizeFilename(name: String?): String {
            if (name == null) return "unknown"
            return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        }

        @JvmStatic
        private fun getExtension(path: String): String {
            val dot = path.lastIndexOf('.')
            return if (dot >= 0) path.substring(dot) else ".jpg"
        }
    }
}
