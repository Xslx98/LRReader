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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.EOFException
import okio.buffer
import okio.sink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads all pages of a LANraragi archive to the download directory.
 * Replaces SpiderQueen's MODE_DOWNLOAD for LANraragi galleries.
 *
 * Reports progress via [SpiderQueen.OnSpiderListener] so DownloadManager
 * can update UI without any changes to its notification pipeline.
 */
class LRRDownloadWorker(context: Context, private val info: DownloadInfo) {

    private val context: Context = context.applicationContext
    private val arcId: String = checkNotNull(info.arcid) { "DownloadInfo.arcid must not be null" }
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
        // Dedicated client for page body GETs:
        //  - 30 s read timeout (large archives may need on-the-fly extraction)
        //  - .cache(null): page responses are not HTTP-cacheable and we are
        //    writing to disk ourselves; skipping the shared 200 MB http_cache
        //    avoids pointless cache-layer bookkeeping per request
        val pageClient = client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .cache(null)
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
            } catch (e: IOException) {
                Log.w(TAG, "Failed to create .nomedia file", e)
            }
        }

        // Step 3: Download pages in parallel
        // LANraragi's /api/archives/:id/page supports concurrent requests and
        // extracts pages on-the-fly if the background Minion job hasn't reached
        // them yet. Hypnotoad (4 workers × 1000 connections) handles this fine.
        val finished = AtomicInteger(0)
        val downloaded = AtomicInteger(0)
        val semaphore = Semaphore(PARALLEL_PAGES)

        val jobs = pagePaths.indices.map { i ->
            scope.async {
                if (cancelled) return@async

                val pagePath = pagePaths[i]
                val ext = getExtension(pagePath)
                val pageFile = File(downloadDir, "%04d%s".format(i + 1, ext))

                // Skip if already downloaded and valid
                if (pageFile.exists() && pageFile.length() > MIN_IMAGE_SIZE && validateImageFile(pageFile)) {
                    val f = finished.incrementAndGet()
                    val d = downloaded.incrementAndGet()
                    listener?.onPageSuccess(i, f, d, total)
                    return@async
                }

                // Acquire semaphore slot — limits concurrent HTTP requests
                semaphore.withPermit {
                    if (cancelled) return@withPermit

                    var success = false
                    for (attempt in 0 until MAX_RETRY) {
                        if (cancelled) break

                        if (attempt > 0) {
                            Log.w(TAG, "Retry page $i (attempt ${attempt + 1})")
                            if (pageFile.exists()) pageFile.delete()
                        }

                        try {
                            // downloadPage validates image magic in-stream (first
                            // 16 bytes peeked from the HTTP response before the
                            // body is written). If that check fails it throws
                            // before the tmp file is renamed, so here we only
                            // verify the final file exists and meets MIN_IMAGE_SIZE.
                            downloadPage(pageClient, pagePath, pageFile, i, total)
                            if (!pageFile.exists() || pageFile.length() < MIN_IMAGE_SIZE) {
                                if (pageFile.exists()) pageFile.delete()
                                throw IOException("Downloaded file too small or missing")
                            }
                            success = true
                            break
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to download page $i (attempt ${attempt + 1})", e)
                            if (attempt == MAX_RETRY - 1) {
                                listener?.onPageFailure(
                                    i, e.message ?: "Unknown error",
                                    finished.get(), downloaded.get(), total
                                )
                            }
                        }
                    }

                    if (success) {
                        val f = finished.incrementAndGet()
                        val d = downloaded.incrementAndGet()
                        listener?.onPageSuccess(i, f, d, total)
                    }
                }
            }
        }

        // Wait for all page downloads to complete (or cancellation)
        try {
            jobs.awaitAll()
        } catch (e: CancellationException) {
            Log.d(TAG, "Download cancelled", e)
        }

        // Step 4: Report finish
        listener?.onFinish(finished.get(), downloaded.get(), total)
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
            // Image bodies (JPEG/PNG/WebP/…) are already compressed; OkHttp's
            // default `Accept-Encoding: gzip` wastes server CPU if the server
            // honours it, and — worse — transparent gzip decoding loses the
            // response's Content-Length, breaking our progress math.
            .header("Accept-Encoding", "identity")
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

                // Validate image magic bytes in-stream: peek the first 16
                // bytes from the HTTP response without consuming them, run
                // the format check, and only then start writing to disk.
                // Saves a post-write disk re-read and aborts corrupt
                // responses before a tmp file is even flushed.
                val source = body.source()
                val header = try {
                    source.peek().readByteArray(HEADER_PEEK_BYTES.toLong())
                } catch (e: EOFException) {
                    throw IOException(
                        "Response too small to validate as image", e
                    )
                }
                if (!validateImageHeader(header, header.size)) {
                    throw IOException("Downloaded data is not a valid image")
                }

                // Stream body → disk via Okio (OkHttp already exposes its
                // body as a BufferedSource; going through `byteStream()` +
                // `FileOutputStream` incurred an extra adapter + byte[]
                // copy for every chunk).
                tmpFile.sink().buffer().use { sink ->
                    // Coalesce progress notifications: each post hops to
                    // the main thread via DownloadEventBus, so emitting one
                    // per 256 KB chunk (~160/s on LAN gigabit with 8
                    // parallel pages) was starving the UI. Accumulate
                    // bytes locally and flush at most every
                    // PROGRESS_NOTIFY_INTERVAL_MS, plus a final flush when
                    // the stream ends.
                    var pendingDelta = 0
                    var lastNotifyNanos = System.nanoTime()
                    val intervalNanos =
                        PROGRESS_NOTIFY_INTERVAL_MS * 1_000_000L
                    while (true) {
                        if (cancelled) {
                            tmpFile.delete()
                            throw IOException("Cancelled")
                        }
                        val read = source.read(sink.buffer, BUFFER_SIZE.toLong())
                        if (read == -1L) break
                        sink.emitCompleteSegments()
                        totalRead += read
                        pendingDelta += read.toInt()
                        if (totalRead > MAX_PAGE_SIZE) {
                            tmpFile.delete()
                            throw IOException(
                                "Page exceeds maximum size limit (${MAX_PAGE_SIZE / 1024 / 1024} MB)"
                            )
                        }
                        if (contentLength > 0) {
                            val now = System.nanoTime()
                            if (now - lastNotifyNanos >= intervalNanos) {
                                listener?.onPageDownload(
                                    index, contentLength, totalRead, pendingDelta
                                )
                                pendingDelta = 0
                                lastNotifyNanos = now
                            }
                        }
                    }
                    // Final flush so the last partial interval isn't lost
                    // (e.g. small pages that finish before the first tick).
                    if (contentLength > 0 && pendingDelta > 0) {
                        listener?.onPageDownload(
                            index, contentLength, totalRead, pendingDelta
                        )
                    }
                    // sink.close() (via use{}) flushes buffered segments to
                    // the underlying FileOutputStream, which in turn flushes
                    // to the OS buffer cache. Explicit fsync remains
                    // omitted: rename-after-close is sufficient for download
                    // integrity; the OS flushes to disk on its own schedule.
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
                    return File(uri.path ?: return null)
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
        private const val BUFFER_SIZE = 262144         // 256KB — reduces syscall overhead on LAN
        private const val MIN_IMAGE_SIZE = 1024L       // 1KB minimum valid image
        private const val MAX_RETRY = 2                // Try up to 2 times per page
        private const val MAX_PAGE_SIZE = 200L * 1024 * 1024 // 200MB per page
        /**
         * Minimum wall time between progress notifications per page. Each
         * notification posts to the main thread via
         * [com.hippo.ehviewer.download.DownloadEventBus], so posting once per
         * 256 KB chunk churned the main looper during fast downloads. 250 ms
         * still gives SpeedTracker (2-second tick) plenty of resolution.
         */
        private const val PROGRESS_NOTIFY_INTERVAL_MS = 250L
        // Concurrent page downloads per archive. 8 is a sweet spot for LAN +
        // pre-extracted archives (Hypnotoad's 4 workers can saturate 8 open
        // page requests without queueing, and NetworkModule now allows 16
        // per-host). Going higher starts to stress server CPU / disk on
        // first-time extractions with diminishing throughput gain.
        private const val PARALLEL_PAGES = 8
        /** Bytes peeked from an image response / file to verify magic. */
        private const val HEADER_PEEK_BYTES = 16

        /**
         * Pure header-byte image format check. Used both by
         * [validateImageFile] (on-disk, for resume) and by [downloadPage]
         * (in-stream, after peeking the HTTP response).
         *
         * Supports JPEG, PNG, GIF, WebP, BMP, AVIF, JPEG XL.
         *
         * @param header bytes read from the start of the image
         * @param read number of valid bytes in [header] (may be < header.size)
         */
        @JvmStatic
        fun validateImageHeader(header: ByteArray, read: Int): Boolean {
            if (read < 4) return false
            return when {
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

        /**
         * Validate that an on-disk file starts with a known image format
         * magic. Used by the resume path (skip already-downloaded pages).
         * Live downloads validate in-stream via [validateImageHeader] so
         * this path is exercised only for pre-existing files.
         */
        @JvmStatic
        fun validateImageFile(file: File): Boolean {
            if (!file.exists() || file.length() < 4) return false
            return try {
                FileInputStream(file).use { fis ->
                    val header = ByteArray(HEADER_PEEK_BYTES)
                    val read = fis.read(header)
                    validateImageHeader(header, read)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to validate image file", e)
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
