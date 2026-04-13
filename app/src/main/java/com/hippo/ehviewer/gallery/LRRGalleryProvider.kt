package com.hippo.ehviewer.gallery

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfo
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.runSuspend
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * GalleryProvider that fetches page images from a LANraragi server.
 *
 * Flow:
 * 1. start() -> calls LRRArchiveApi.extractArchive() on IO thread to get page paths
 * 2. onRequest(index) -> downloads the specific page image, decodes it, notifies UI
 * 3. Adjacent pages are preloaded (download only) for faster navigation
 */
class LRRGalleryProvider(context: Context, private val galleryInfo: GalleryInfo) : GalleryProvider2() {

    /**
     * Immutable snapshot of provider state. All three fields are read/written atomically
     * via [stateRef] to eliminate multi-field race conditions (STAB-2).
     */
    private data class ProviderState(
        val paths: Array<String>? = null,
        val count: Int = GalleryProvider.STATE_WAIT,
        val stopped: Boolean = false
    )

    private val context: Context = context.applicationContext
    private val arcId: String = galleryInfo.token ?: "" // arcid stored in token by toGalleryInfo()
    private val serverUrl: String = LRRAuthManager.getServerUrl() ?: ""

    // Atomic provider state -- replaces individual @Volatile fields for pagePaths/pageCount/stopped
    private val stateRef = AtomicReference(ProviderState())

    @Volatile
    private var errorMessage: String? = null

    // Cache directory for downloaded pages
    private lateinit var cacheDir: File

    // Shared OkHttpClient for page downloads (created once, reused)
    @Volatile
    private var pageClient: OkHttpClient? = null

    // Scope for all background work launched by this provider. Created in
    // start() and cancelled in stop() so that pending downloads, preloads,
    // and cache evictions don't outlive the gallery session.
    // SupervisorJob: one failing job doesn't cancel siblings.
    @Volatile
    private var providerScope: CoroutineScope? = null

    // Track start page for reading progress
    private var startPageValue: Int = loadReadingProgress(this.context, galleryInfo.gid)


    override fun start() {
        super.start()

        // Initialize the provider's coroutine scope. Every background launch
        // in this class is scoped here and cancelled in stop().
        providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Prepare cache directory
        cacheDir = File(context.cacheDir, "lrr_pages/$arcId")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        // Record access time for LRU cache eviction (replaces unreliable File.lastModified())
        context.getSharedPreferences(SP_CACHE_ACCESS, Context.MODE_PRIVATE)
            .edit()
            .putLong(arcId, System.currentTimeMillis())
            .apply()

        // Create shared OkHttpClient with longer timeout for page downloads
        val baseClient = ServiceRegistry.networkModule.okHttpClient
        pageClient = baseClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // Create .nomedia to prevent gallery apps from scanning cached images
        val noMedia = File(cacheDir, ".nomedia")
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile()
            } catch (e: IOException) {
                Log.d(TAG, "Create .nomedia file", e)
            }
        }

        // Extract archive on background thread
        providerScope?.launch {
            try {
                val client = ServiceRegistry.networkModule.longReadClient
                val pages = runSuspend {
                    LRRArchiveApi.getFileList(
                        ServiceRegistry.networkModule.longReadClient,
                        serverUrl, arcId
                    )
                }
                stateRef.set(ProviderState(paths = pages, count = pages.size))
                Log.d(TAG, "Extracted ${pages.size} pages for $arcId")

                // Clear "new" flag
                try {
                    runSuspend<Unit> {
                        LRRArchiveApi.clearNewFlag(client, serverUrl, arcId)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Clear new flag for archive $arcId", e)
                }

                // Load server-side reading progress
                var serverPage = startPageValue // fallback to local SP value
                Log.i(TAG, "[PROGRESS] Local SP page=$startPageValue for gid=${galleryInfo.gid}")
                try {
                    val metadata = runSuspend {
                        LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcId)
                    }
                    Log.i(
                        TAG,
                        "[PROGRESS] Server metadata: progress=${metadata.progress}" +
                            " lastreadtime=${metadata.lastreadtime} arcid=${metadata.arcid}"
                    )
                    if (metadata.progress > 0) {
                        val serverPage0 = metadata.progress - 1 // convert 1-indexed to 0-indexed
                        val serverTs = metadata.lastreadtime
                        val localTs = loadReadingTimestamp(context, galleryInfo.gid)
                        Log.i(
                            TAG,
                            "[PROGRESS] serverPage0=$serverPage0 serverTs=$serverTs localTs=$localTs"
                        )
                        // Use whichever is more recent by timestamp
                        if (serverTs > localTs) {
                            serverPage = serverPage0
                            startPageValue = serverPage0
                            saveReadingProgress(context, galleryInfo.gid, serverPage0)
                            Log.i(TAG, "[PROGRESS] Using SERVER progress: page $serverPage0")
                        } else if (localTs > serverTs && startPageValue > 0) {
                            serverPage = startPageValue
                            Log.i(TAG, "[PROGRESS] Using LOCAL progress: page $startPageValue")
                        } else {
                            // Equal timestamps or both zero — use the larger page number
                            serverPage = maxOf(serverPage0, startPageValue)
                            startPageValue = serverPage
                            Log.i(TAG, "[PROGRESS] Timestamps equal, using max page: $serverPage")
                        }
                    } else {
                        Log.i(TAG, "[PROGRESS] Server progress=0, using local page=$startPageValue")
                        serverPage = startPageValue
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[PROGRESS] Failed to load server progress: ${e.message}")
                }

                val finalPage = serverPage
                Log.i(TAG, "[PROGRESS] Final resolved page=$finalPage")

                // Notify UI that data is ready
                notifyDataChanged()

                // Jump GalleryView to the resolved page via coroutine + Dispatchers.Main
                // (getStartPage() was read synchronously before this async callback)
                if (finalPage > 0) {
                    val gv = galleryView
                    Log.i(TAG, "[PROGRESS] GalleryView ref=${if (gv != null) "OK" else "NULL"}")
                    if (gv != null) {
                        // Delay to ensure GL layout is attached; launched on
                        // providerScope so stop() cancellation prevents stale View access.
                        providerScope?.launch {
                            delay(300)
                            if (stateRef.get().stopped) return@launch
                            val gvRef = galleryView ?: return@launch
                            withContext(Dispatchers.Main) {
                                if (!stateRef.get().stopped) {
                                    gvRef.setCurrentPage(finalPage)
                                    Log.i(TAG, "[PROGRESS] setCurrentPage($finalPage) called")
                                } else {
                                    Log.w(TAG, "[PROGRESS] GalleryView gone before setCurrentPage")
                                }
                            }
                        }
                    }
                }

                // Preload pages from start page position
                preloadPages(finalPage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract archive: ${e.message}", e)
                errorMessage = "Failed to load pages: ${e.message}"
                stateRef.updateAndGet { it.copy(count = GalleryProvider.STATE_ERROR) }
                notifyDataChanged()
            }
        }
    }

    override fun stop() {
        super.stop()
        stateRef.updateAndGet { it.copy(stopped = true) }
        pageClient = null
        // Safe to clear: stopped flag prevents new requests from entering onRequest()
        inflightRequests.clear()

        // Evict old archive caches on the application-scoped IO scope so the
        // cleanup still runs after the provider's own scope is cancelled below.
        ServiceRegistry.coroutineModule.ioScope.launch {
            cleanupOldCaches(context, MAX_TOTAL_CACHE_BYTES)
        }

        // Cancel all in-flight provider jobs (start extraction, onRequest
        // downloads, preloads, putStartPage progress sync).
        providerScope?.cancel()
        providerScope = null
    }

    override fun getStartPage(): Int = startPageValue

    override fun putStartPage(page: Int) {
        startPageValue = page

        // Persist locally for instant restore on next open
        saveReadingProgress(context, galleryInfo.gid, page)

        // Sync progress to LANraragi server (1-indexed). Use the app-wide IO
        // scope: putStartPage may be called during stop()/teardown when the
        // provider scope is already cancelled, and we still want to persist.
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                runSuspend<Unit> {
                    LRRArchiveApi.updateProgress(client, serverUrl, arcId, page + 1)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync progress: ${e.message}")
            }
        }
    }

    override fun size(): Int = stateRef.get().count

    override fun getImageFilename(index: Int): String {
        return String.format(
            Locale.US,
            "lrr-%s-%04d",
            arcId.substring(0, minOf(8, arcId.length)),
            index + 1
        )
    }

    override fun save(index: Int, file: UniFile): Boolean {
        val cached = getCacheFile(index)
        if (!cached.exists()) return false
        return try {
            FileInputStream(cached).use { fis ->
                file.openOutputStream().use { os ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        os.write(buffer, 0, read)
                    }
                }
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save page $index", e)
            false
        }
    }

    override fun save(index: Int, dir: UniFile, filename: String): UniFile? {
        val cached = getCacheFile(index)
        if (!cached.exists()) return null
        val dst = dir.createFile(filename) ?: return null
        return if (save(index, dst)) dst else null
    }

    override fun onRequest(index: Int) {
        val state = stateRef.get()
        val paths = state.paths
        if (paths == null || index < 0 || index >= paths.size) {
            notifyPageFailed(index, "Page not available")
            return
        }

        // Skip if this page is already being downloaded
        if (inflightRequests.putIfAbsent(index, true) != null) {
            return
        }

        notifyPageWait(index)

        // Download and decode on IO thread. Launch on the provider scope so
        // that stop() can cancel pending downloads.
        val scope = providerScope
        if (scope == null) {
            // stop() was called after onRequest arrived — skip silently
            inflightRequests.remove(index)
            return
        }
        scope.launch {
            try {
                downloadAndDecodePage(index)

                // Preload adjacent pages after successful load
                preloadPages(index)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load page $index: ${e.message}", e)
                notifyPageFailed(index, e.message)
            } finally {
                inflightRequests.remove(index)
            }
        }
    }

    override fun onForceRequest(index: Int) {
        // Delete cached file and re-request
        val cached = getCacheFile(index)
        if (cached.exists()) {
            cached.delete()
        }
        inflightRequests.remove(index)
        onRequest(index)
    }

    override fun onCancelRequest(index: Int) {
        // No-op for now (OkHttp doesn't support per-request cancellation easily here)
    }

    override fun getError(): String = errorMessage ?: "Unknown error"

    // ==================== Internal ====================

    private fun getCacheFile(index: Int): File = File(cacheDir, "page_$index")

    // Striped locks to prevent concurrent downloads of the same page (PERF-6).
    // Fixed-size array replaces unbounded ConcurrentHashMap<Int, Any>.
    private val pageLocks = Array(STRIPE_COUNT) { Any() }

    // Track in-flight page requests to avoid submitting duplicate tasks to the thread pool
    private val inflightRequests = ConcurrentHashMap<Int, Boolean>()

    private fun getPageLock(index: Int): Any = pageLocks[index.and(STRIPE_COUNT - 1)]

    /** Progress callback interface for download progress reporting */
    private fun interface ProgressCallback {
        fun onProgress(index: Int, percent: Float)
    }

    /**
     * Download a page to cache if not already cached.
     * Uses atomic write (temp file + fsync + rename) to prevent corruption.
     * Thread-safe: per-page striped locking prevents concurrent downloads of the same page.
     *
     * @param progressCallback if non-null, called with (index, percent) during download
     */
    @Throws(IOException::class)
    private fun downloadPageToCache(index: Int, progressCallback: ProgressCallback? = null) {
        val cacheFile = getCacheFile(index)
        if (cacheFile.exists() && cacheFile.length() > MIN_IMAGE_SIZE) {
            return // Already cached and sufficiently large
        }

        synchronized(getPageLock(index)) {
            // Check stop flag before downloading
            if (stateRef.get().stopped) return
            // Double-check after acquiring lock
            if (cacheFile.exists() && cacheFile.length() > MIN_IMAGE_SIZE) {
                return
            }

            // Delete any corrupt/tiny cached file before re-downloading
            if (cacheFile.exists()) {
                cacheFile.delete()
            }

            val tmpFile = File(cacheDir, "page_$index.${Thread.currentThread().id}.tmp")
            try {
                val state = stateRef.get()
                val paths = state.paths
                    ?: throw IOException("Page paths not loaded yet")
                if (index >= paths.size) {
                    throw IOException("Page index $index out of bounds (size=${paths.size})")
                }
                val pageUrl = serverUrl + paths[index]
                // Use shared page client with 30s timeout (created once in start())
                val currentPageClient = pageClient
                    ?: ServiceRegistry.networkModule.okHttpClient
                val request = Request.Builder()
                    .url(pageUrl)
                    .get()
                    .build()

                var contentLength: Long = -1
                var totalRead: Long = 0

                currentPageClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val body = response.body
                        ?: throw IOException("Empty response body for page $index")
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
                                        index,
                                        totalRead.toFloat() / contentLength
                                    )
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
                    throw IOException(
                        "Incomplete download: expected $contentLength bytes, got $totalRead"
                    )
                }

                // Validate minimum size (catches empty/truncated chunked responses)
                if (totalRead < MIN_IMAGE_SIZE) {
                    tmpFile.delete()
                    throw IOException(
                        "Downloaded file too small ($totalRead bytes), likely corrupt"
                    )
                }

                // Validate image magic bytes
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
                // Clean up tmp file on any failure
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
            }
        }
    }

    /**
     * Validate that a file starts with a known image format magic bytes.
     * Supports JPEG, PNG, GIF, WebP, BMP, AVIF, JPEG XL.
     */
    private fun validateImageFile(file: File): Boolean {
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

                // AVIF: ....ftypavif (ftyp box at offset 4, major brand 'avif' at offset 8)
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

    @Throws(IOException::class)
    private fun downloadAndDecodePage(index: Int) {
        // Try up to 2 times: once normally, once with cache invalidation
        for (attempt in 0 until 2) {
            var cacheFile = getCacheFile(index)

            // On retry, wait 1s for network recovery, then force re-download
            if (attempt > 0) {
                Log.w(TAG, "Retry page $index (attempt ${attempt + 1}), waiting 1s...")
                LockSupport.parkNanos(RETRY_DELAY_NANOS)
                if (Thread.interrupted()) return // Respect interruption during park
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
            }

            // Download (with progress reporting via notifyPagePercent)
            downloadPageToCache(index) { idx, percent -> notifyPagePercent(idx, percent) }

            // Validate cached file before decode
            cacheFile = getCacheFile(index)
            if (!cacheFile.exists() || cacheFile.length() < MIN_IMAGE_SIZE) {
                if (attempt == 0) continue // Retry
                notifyPageFailed(index, context.getString(R.string.lrr_download_failed_invalid))
                return
            }

            if (!validateImageFile(cacheFile)) {
                cacheFile.delete()
                if (attempt == 0) continue // Retry
                notifyPageFailed(index, context.getString(R.string.lrr_download_failed_not_image))
                return
            }

            // Decode image
            var fis: FileInputStream? = null
            try {
                fis = FileInputStream(cacheFile)
                val image = Image.decode(fis, false)
                if (image != null) {
                    notifyPageSucceed(index, image)
                    return // Success!
                } else {
                    // Decode returned null — file is corrupt
                    cacheFile.delete()
                    if (attempt == 0) continue // Retry
                    notifyPageFailed(index, context.getString(R.string.lrr_decode_failed))
                    return
                }
            } finally {
                fis?.close()
            }
        }
    }

    /**
     * Preload adjacent pages (download only, no decode).
     * Downloads sequentially on a single IO thread to avoid overloading the server.
     */
    private fun preloadPages(currentIndex: Int) {
        val state = stateRef.get()
        if (state.paths == null) return
        providerScope?.launch {
            for (i in 1..PRELOAD_COUNT) {
                val currentState = stateRef.get()
                if (currentState.stopped) break
                val preloadIndex = currentIndex + i
                val paths = currentState.paths ?: break
                if (preloadIndex >= paths.size) break

                val cached = getCacheFile(preloadIndex)
                if (cached.exists() && cached.length() > MIN_IMAGE_SIZE) continue

                try {
                    downloadPageToCache(preloadIndex)
                    Log.d(TAG, "Preloaded page $preloadIndex")
                } catch (e: Exception) {
                    Log.d(TAG, "Preload failed for page $preloadIndex: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "LRRGalleryProvider"
        private const val BUFFER_SIZE = 65536 // 64KB buffer for LAN transfers
        private const val PRELOAD_COUNT = 5 // Preload next 5 pages (LAN is fast)
        private const val MAX_TOTAL_CACHE_BYTES = 500L * 1024L * 1024L // 500MB total cache limit
        private const val SP_CACHE_ACCESS = "lrr_cache_access" // SharedPreferences name for cache access timestamps
        private const val MIN_IMAGE_SIZE = 1024L // Minimum valid image file size (1KB)
        private const val STRIPE_COUNT = 32 // Number of striped locks for page downloads (PERF-6)
        private const val RETRY_DELAY_NANOS = 1_000_000_000L // 1 second in nanos for retry delay

        /**
         * Evict oldest archive cache directories until total size is within limit.
         * Uses SharedPreferences access time as LRU indicator.
         */
        @JvmStatic
        fun cleanupOldCaches(context: Context, maxTotalBytes: Long) {
            val parentDir = File(context.cacheDir, "lrr_pages")
            if (!parentDir.exists() || !parentDir.isDirectory) return

            val archiveDirs = parentDir.listFiles() ?: return
            if (archiveDirs.isEmpty()) return

            // Calculate total size
            var totalSize: Long = 0
            val dirList = archiveDirs.toMutableList()
            for (dir in dirList) {
                totalSize += getDirSize(dir)
            }

            if (totalSize <= maxTotalBytes) return

            // Sort by SharedPreferences access time (oldest first), falling back to 0 for unrecorded dirs
            val sp = context.getSharedPreferences(SP_CACHE_ACCESS, Context.MODE_PRIVATE)
            dirList.sortWith(Comparator.comparingLong { dir -> sp.getLong(dir.name, 0L) })

            val editor = sp.edit()
            for (dir in dirList) {
                if (totalSize <= maxTotalBytes) break
                val dirSize = getDirSize(dir)
                deleteDir(dir)
                editor.remove(dir.name) // Clean up stale SP entry
                totalSize -= dirSize
                Log.d(TAG, "Evicted cache: ${dir.name} (${dirSize / 1024} KB)")
            }
            editor.apply()
        }

        private fun getDirSize(dir: File): Long {
            val files = dir.listFiles() ?: return 0
            var size: Long = 0
            for (f in files) {
                size += f.length()
            }
            return size
        }

        private fun deleteDir(dir: File) {
            val files = dir.listFiles()
            if (files != null) {
                for (f in files) {
                    f.delete()
                }
            }
            dir.delete()
        }
    }
}
