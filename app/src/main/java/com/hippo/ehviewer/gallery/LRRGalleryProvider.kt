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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
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

        // Prepare cache directory (handles .nomedia and LRU access timestamp)
        cacheDir = ReaderPageCache.ensureCacheDir(context, arcId)

        // Create shared OkHttpClient with longer timeout for page downloads
        val baseClient = ServiceRegistry.networkModule.okHttpClient
        pageClient = baseClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // Load page list and metadata on background threads
        providerScope?.launch {
            try {
                val client = ServiceRegistry.networkModule.longReadClient

                // Fire-and-forget: clear "new" flag (independent, no need to wait)
                launch {
                    try {
                        LRRArchiveApi.clearNewFlag(client, serverUrl, arcId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Clear new flag for archive $arcId", e)
                    }
                }

                // Parallel: fetch file list + server metadata
                val pagesDeferred = async {
                    LRRArchiveApi.getFileList(client, serverUrl, arcId)
                }
                val metadataDeferred = async {
                    try {
                        LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcId)
                    } catch (e: Exception) {
                        Log.w(TAG, "[PROGRESS] Failed to load server progress: ${e.message}")
                        null
                    }
                }

                // As soon as file list is ready, notify UI — page requests can start immediately
                val pages = pagesDeferred.await()
                stateRef.set(ProviderState(paths = pages, count = pages.size))
                Log.d(TAG, "Extracted ${pages.size} pages for $arcId")
                notifyDataChanged()

                // Resolve reading progress from server metadata (may already be available)
                val metadata = metadataDeferred.await()
                var serverPage = startPageValue
                Log.i(TAG, "[PROGRESS] Local SP page=$startPageValue for gid=${galleryInfo.gid}")

                if (metadata != null) {
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
                        if (serverTs > localTs) {
                            serverPage = serverPage0
                            startPageValue = serverPage0
                            saveReadingProgress(context, galleryInfo.gid, serverPage0)
                            Log.i(TAG, "[PROGRESS] Using SERVER progress: page $serverPage0")
                        } else if (localTs > serverTs && startPageValue > 0) {
                            serverPage = startPageValue
                            Log.i(TAG, "[PROGRESS] Using LOCAL progress: page $startPageValue")
                        } else {
                            serverPage = maxOf(serverPage0, startPageValue)
                            startPageValue = serverPage
                            Log.i(TAG, "[PROGRESS] Timestamps equal, using max page: $serverPage")
                        }
                    } else {
                        Log.i(TAG, "[PROGRESS] Server progress=0, using local page=$startPageValue")
                        serverPage = startPageValue
                    }
                }

                val finalPage = serverPage
                Log.i(TAG, "[PROGRESS] Final resolved page=$finalPage")

                // Jump GalleryView to the resolved page.
                // setCurrentPage() posts to GL method queue, notifyDataChanged() posted
                // to GL idle first — GL processes them in order, no delay needed.
                if (finalPage > 0) {
                    val gv = galleryView
                    Log.i(TAG, "[PROGRESS] GalleryView ref=${if (gv != null) "OK" else "NULL"}")
                    if (gv != null) {
                        providerScope?.launch {
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

                // Start preloading from resolved page
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
            ReaderPageCache.cleanupOldCaches(context)
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

    /**
     * Download a page to cache if not already cached.
     * Thread-safe: per-page striped locking prevents concurrent downloads of the same page.
     * Delegates the actual download + validation to [ReaderPageCache.downloadToFile].
     */
    @Throws(IOException::class)
    private fun downloadPageToCache(
        index: Int,
        progressCallback: ReaderPageCache.ProgressCallback? = null
    ) {
        val cacheFile = getCacheFile(index)
        if (cacheFile.exists() && cacheFile.length() > ReaderPageCache.MIN_IMAGE_SIZE) {
            return // Already cached and sufficiently large
        }

        synchronized(getPageLock(index)) {
            if (stateRef.get().stopped) return
            if (cacheFile.exists() && cacheFile.length() > ReaderPageCache.MIN_IMAGE_SIZE) {
                return
            }

            val state = stateRef.get()
            val paths = state.paths
                ?: throw IOException("Page paths not loaded yet")
            if (index >= paths.size) {
                throw IOException("Page index $index out of bounds (size=${paths.size})")
            }
            val pageUrl = serverUrl + paths[index]
            val currentPageClient = pageClient
                ?: ServiceRegistry.networkModule.okHttpClient

            ReaderPageCache.downloadToFile(
                currentPageClient, pageUrl, cacheFile, index, progressCallback
            )
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
            if (!cacheFile.exists() || cacheFile.length() < ReaderPageCache.MIN_IMAGE_SIZE) {
                if (attempt == 0) continue // Retry
                notifyPageFailed(index, context.getString(R.string.lrr_download_failed_invalid))
                return
            }

            if (!ReaderPageCache.validateImageFile(cacheFile)) {
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
     * Uses a semaphore to allow [PRELOAD_PARALLELISM] concurrent downloads,
     * utilizing more of the available bandwidth without overloading the server.
     */
    private fun preloadPages(currentIndex: Int) {
        val paths = stateRef.get().paths ?: return
        providerScope?.launch {
            val semaphore = Semaphore(PRELOAD_PARALLELISM)
            for (i in 1..PRELOAD_COUNT) {
                if (stateRef.get().stopped) break
                val preloadIndex = currentIndex + i
                if (preloadIndex >= paths.size) break
                val cached = getCacheFile(preloadIndex)
                if (cached.exists() && cached.length() > ReaderPageCache.MIN_IMAGE_SIZE) continue

                launch {
                    semaphore.acquire()
                    try {
                        if (!stateRef.get().stopped) {
                            downloadPageToCache(preloadIndex)
                            Log.d(TAG, "Preloaded page $preloadIndex")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Preload failed for page $preloadIndex: ${e.message}")
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "LRRGalleryProvider"
        private const val BUFFER_SIZE = 65536 // 64KB buffer for save()
        private const val PRELOAD_COUNT = 5 // Preload next 5 pages (LAN is fast)
        private const val PRELOAD_PARALLELISM = 2 // Concurrent preload downloads
        private const val STRIPE_COUNT = 32 // Number of striped locks for page downloads (PERF-6)
        private const val RETRY_DELAY_NANOS = 1_000_000_000L // 1 second in nanos for retry delay
    }
}
