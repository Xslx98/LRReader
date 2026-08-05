package com.hippo.ehviewer.gallery

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.GetText
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LrrFileListCache
import com.lanraragi.reader.client.api.resolvePageUrl
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * GalleryProvider that fetches page images from a LANraragi server.
 *
 * Flow:
 * 1. start() -> calls LRRArchiveApi.extractArchive() on IO thread to get page paths
 * 2. onRequest(index) -> downloads the specific page image, decodes it, notifies UI
 * 3. Adjacent pages are preloaded (download only) for faster navigation
 */
class LRRGalleryProvider(
    context: Context,
    private val arcId: String,
    private val serverProfileId: Long = 0L,
) : GalleryProvider2() {

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

    /**
     * Source-profile base URL, resolved once at the top of [start] from
     * [serverProfileId] via [resolveSourceBaseUrl] so page downloads,
     * metadata and progress sync hit the server this archive belongs to —
     * not whatever profile is active (CLAUDE.md multi-profile rule). The
     * [serverUrlDeferred] is completed on the app IO scope (so a quick
     * stop() can't strand the progress syncer awaiting it); [serverUrl] is
     * the resolved value, set before the page-list fetch and therefore
     * before any onRequest page download can run.
     */
    @Volatile
    private var serverUrl: String = ""
    private val serverUrlDeferred = CompletableDeferred<String?>()

    // Atomic provider state -- replaces individual @Volatile fields for pagePaths/pageCount/stopped
    private val stateRef = AtomicReference(ProviderState())

    // Provider-scoped so PRELOAD_PARALLELISM is a true global cap: several
    // pages bind near-simultaneously in scroll mode, and a per-invocation
    // semaphore would let each preloadPages batch independently admit
    // PRELOAD_PARALLELISM downloads (effective concurrency ≈ 2 × batches).
    private val preloadSemaphore = Semaphore(PRELOAD_PARALLELISM)

    /** Serialized + conflated server progress sync (app-scoped, survives stop()). */
    private val progressSyncer = ReadingProgressSyncer(
        ServiceRegistry.coroutineModule.ioScope
    ) sync@{ page0 ->
        val url = serverUrlDeferred.await() ?: return@sync
        val client = ServiceRegistry.networkModule.okHttpClient
        LRRArchiveApi.updateProgress(client, url, arcId, page0 + 1)
    }

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

    // Track start page for reading progress. Written from the IO-scope
    // metadata reconcile and putStartPage (GL/main threads), read by
    // getStartPage on the GL thread — volatile for cross-thread visibility.
    @Volatile
    private var startPageValue: Int = loadReadingProgress(this.context, arcId)

    // Local-only intra-page scroll fraction (0.0 ~ 1.0) restored on
    // start from ARCHIVE_LOCAL_STATE.HISTORY_SCROLL_FRACTION; written
    // back via putScrollFraction. Only meaningful when reading in
    // LAYOUT_TOP_TO_BOTTOM (vertical long-strip) mode.
    @Volatile
    private var pendingStartScrollFraction: Float = 0f

    /** See DirGalleryProvider.userNavigated — same guard for the online reader. */
    @Volatile
    private var userNavigated = false

    /** See DirGalleryProvider.startPageBaseline — snapshot taken in start(). */
    @Volatile
    private var startPageBaseline = 0


    override fun start() {
        super.start()
        userNavigated = false
        startPageBaseline = startPageValue

        // Resolve the archive's source-profile base URL once, app-scoped so a
        // stop() that cancels providerScope can't strand the progress syncer
        // (which awaits this deferred). The page-list coroutine below awaits
        // the same deferred before fetching anything.
        ServiceRegistry.coroutineModule.ioScope.launch {
            if (!serverUrlDeferred.isCompleted) {
                val url = try {
                    resolveSourceBaseUrl(
                        serverProfileId,
                        ServiceRegistry.dataModule.profileLookupCache,
                    )
                } catch (e: Exception) {
                    // resolveSourceBaseUrl never returns null — a deleted /
                    // orphaned source profile (or no active profile on the
                    // id==0 path) THROWS. Swallow to null here; the page-list
                    // coroutine turns that into an inline STATE_ERROR.
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Source profile resolve failed for arcid=$arcId: ${e.message}")
                    }
                    null
                }
                serverUrlDeferred.complete(url)
            }
        }

        // Initialize the provider's coroutine scope. Every background launch
        // in this class is scoped here and cancelled in stop(). Install the
        // app-wide [com.hippo.ehviewer.module.CoroutineModule.exceptionHandler]
        // so any uncaught exception (e.g. an `async` child whose failure
        // races the outer `try { … pagesDeferred.await() … }` and reaches
        // the supervisor before await rethrows) is logged + reported to
        // Analytics instead of taking down the process via the default
        // thread UEH. Without this, a single LRRHttpException from the
        // file-list fetch crashed the gallery viewer when an archive's
        // local files were unreachable and the streaming fallback hit a
        // different server's 400.
        providerScope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                ServiceRegistry.coroutineModule.exceptionHandler
        )

        // Prepare cache directory (handles .nomedia and LRU access timestamp)
        cacheDir = ReaderPageCache.ensureCacheDir(context, arcId)

        // Shared page-streaming client (no call cap, no HTTP cache) — see
        // INetworkModule.pageStreamClient for the rationale.
        pageClient = ServiceRegistry.networkModule.pageStreamClient

        // Load page list and metadata on background threads
        providerScope?.launch {
            try {
                // Wait for the source-profile URL before any fetch. Unlike
                // the Dir provider (whose file list is local and resolves in
                // parallel), this await is intrinsic: getFileList needs the
                // URL. A null resolution (the swallowed throw above) means
                // there is no server to stream from — surface an inline error
                // instead of an indefinite spinner.
                val resolvedUrl = serverUrlDeferred.await()
                if (resolvedUrl == null) {
                    errorMessage = GetText.getString(R.string.lrr_error_load_pages_failed)
                    stateRef.updateAndGet { it.copy(count = GalleryProvider.STATE_ERROR) }
                    notifyDataChanged()
                    return@launch
                }
                serverUrl = resolvedUrl

                val client = ServiceRegistry.networkModule.longReadClient

                // Fire-and-forget: clear "new" flag (independent, no need to wait)
                launch {
                    try {
                        LRRArchiveApi.clearNewFlag(client, serverUrl, arcId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Clear new flag for archive $arcId", e)
                    }
                }

                // Parallel: fetch file list + server metadata + local fraction
                // The file list is the typical cold-open bottleneck on a
                // server with many files per archive. Try the in-memory
                // cross-session cache first; on miss fetch and store.
                //
                // The fetch is wrapped in its own try/catch and returns null
                // on failure — without this, a transport-level error (e.g.
                // HTTP 400 because the archive lives on a different profile
                // than the active one, or a network drop) would propagate
                // out of `async` to the parent Job and reach the supervisor
                // unhandled, crashing the process even though the outer
                // `start` body has its own catch. Sentinel + null check is
                // the idiom that survives any exception-propagation-vs-
                // cancellation race here.
                val pagesDeferred = async {
                    try {
                        LrrFileListCache.get(serverUrl, arcId) ?: run {
                            val fetched = LRRArchiveApi.getFileList(client, serverUrl, arcId)
                            LrrFileListCache.put(serverUrl, arcId, fetched)
                            fetched
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "[FETCH] file list failed for arcid=$arcId", e)
                        null
                    }
                }
                val metadataDeferred = async {
                    // Bounded: longReadClient has a 120s read timeout (sized for
                    // archive extraction); a restore decision must not wait near
                    // that long. withTimeoutOrNull is the coroutine backstop and
                    // the callTimeout-bounded client aborts the in-flight HTTP
                    // attempt near the same deadline.
                    val metaClient = client.newBuilder()
                        .callTimeout(METADATA_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build()
                    withTimeoutOrNull(METADATA_TIMEOUT_MS) {
                        try {
                            LRRArchiveApi.getArchiveMetadata(metaClient, serverUrl, arcId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "[PROGRESS] Failed to load server progress: ${e.message}")
                            null
                        }
                    }
                }
                val fractionDeferred = async {
                    try {
                        ServiceRegistry.dataModule.historyRepository
                            .getHistoryScrollFraction(arcId, serverProfileId) ?: 0f
                    } catch (e: Exception) {
                        Log.w(TAG, "[PROGRESS] Failed to load scroll fraction: ${e.message}")
                        0f
                    }
                }

                // As soon as file list is ready, notify UI — page requests can start immediately
                val pages = pagesDeferred.await()
                if (pages == null) {
                    // file-list fetch failed (handled inside the async); surface
                    // a STATE_ERROR so GalleryActivity shows the inline error
                    // view instead of an indefinite spinner.
                    errorMessage = GetText.getString(R.string.lrr_error_load_pages_failed)
                    stateRef.updateAndGet { it.copy(count = GalleryProvider.STATE_ERROR) }
                    notifyDataChanged()
                    return@launch
                }
                stateRef.set(ProviderState(paths = pages, count = pages.size))
                Log.d(TAG, "Extracted ${pages.size} pages for $arcId")

                // Publish the warm decoded slot for the known local start page
                // BEFORE notifyDataChanged(), mirroring DirGalleryProvider:
                // notifyPageSucceed puts the image in the provider ImageCache so
                // the layout's first bind of the start page is a cache HIT and
                // never launches a redundant decode whose late delivery would
                // swap the texture and replay the 300ms fade-in (the open
                // flicker). The post-reconciliation consume below still covers
                // the case where server progress resolves to a different page:
                // consumeDecodedPage is index-matched and leaves a non-matching
                // slot intact, so at most one of the two consumes hits.
                val warmStartPage = (
                    if (initialPageOverride >= 0) initialPageOverride else startPageValue
                    ).coerceIn(0, pages.size - 1)
                val warmedStart = ReaderPageCache.consumeDecodedPage(
                    arcId, warmStartPage, awaitInflightWarmMs = WARM_AWAIT_MS
                )
                if (warmedStart != null) {
                    Log.i(TAG, "[PROGRESS] decoded slot HIT (pre-notify) for page=$warmStartPage")
                    notifyPageSucceed(warmStartPage, warmedStart)
                }

                notifyDataChanged()

                // Resolve reading progress from server metadata (may already be available)
                val metadata = metadataDeferred.await()
                var serverPage = startPageValue
                Log.i(TAG, "[PROGRESS] Local SP page=$startPageValue for arcid=$arcId")

                if (metadata != null) {
                    Log.i(
                        TAG,
                        "[PROGRESS] Server metadata: progress=${metadata.progress}" +
                            " lastreadtime=${metadata.lastreadtime} arcid=${metadata.arcid}"
                    )
                    val localTs = loadReadingTimestamp(context, arcId)
                    val resolved = ReadingProgressReconciler.resolve(
                        startPageValue, localTs, metadata.progress, metadata.lastreadtime
                    )
                    Log.i(
                        TAG,
                        "[PROGRESS] resolved page=$resolved (localPage0=$startPageValue/$localTs" +
                            " serverProgress1=${metadata.progress}/${metadata.lastreadtime})"
                    )
                    if (resolved != startPageValue) {
                        startPageValue = resolved
                        saveReadingProgress(context, arcId, resolved)
                    }
                    serverPage = resolved
                }

                val finalPage = serverPage
                val finalFraction = fractionDeferred.await().coerceIn(0f, 1f)
                pendingStartScrollFraction = finalFraction
                Log.i(
                    TAG,
                    "[PROGRESS] Final resolved page=$finalPage fraction=$finalFraction"
                )

                // Try the cross-session decoded slot (filled by the
                // detail-page warmup or the openHelper trigger). On a
                // hit we hand the Image straight to the GL pipeline.
                // awaitInflightWarmMs bridges the openHelper-trigger
                // race: when warm and provider start race in parallel,
                // a brief wait (here 300ms, ample for ~280ms LRR
                // decode) turns the otherwise-MISS into a HIT.
                val warmed = ReaderPageCache.consumeDecodedPage(
                    arcId, finalPage, awaitInflightWarmMs = WARM_AWAIT_MS
                )
                if (warmed != null) {
                    Log.i(TAG, "[PROGRESS] decoded slot HIT for page=$finalPage")
                    notifyPageSucceed(finalPage, warmed)
                }

                // Jump GalleryView to the resolved page (and apply the
                // intra-page fraction if any). setCurrentPage() and
                // setCurrentPageScrollFraction() both post to the GL
                // method queue, which is drained in order — no delay
                // needed.
                if (!userNavigated && (finalPage > 0 || finalFraction > 0f)) {
                    val gv = galleryView
                    Log.i(TAG, "[PROGRESS] GalleryView ref=${if (gv != null) "OK" else "NULL"}")
                    if (gv != null) {
                        providerScope?.launch {
                            if (stateRef.get().stopped) return@launch
                            val gvRef = galleryView ?: return@launch
                            withContext(Dispatchers.Main) {
                                if (!stateRef.get().stopped && !userNavigated) {
                                    // Defensive: an empty page list would make
                                    // coerceIn throw (max < min). Mirror
                                    // DirGalleryProvider's guard.
                                    val maxIndex = pages.size - 1
                                    val target = if (maxIndex >= 0) {
                                        finalPage.coerceIn(0, maxIndex)
                                    } else {
                                        finalPage
                                    }
                                    if (finalFraction > 0f) {
                                        gvRef.setCurrentPageScrollFraction(target, finalFraction)
                                        Log.i(
                                            TAG,
                                            "[PROGRESS] setCurrentPageScrollFraction(" +
                                                "$target, $finalFraction) called"
                                        )
                                    } else {
                                        gvRef.setCurrentPage(target)
                                        Log.i(TAG, "[PROGRESS] setCurrentPage($target) called")
                                    }
                                } else {
                                    Log.w(TAG, "[PROGRESS] skip jump (stopped or user navigated)")
                                }
                            }
                        }
                    }
                } else if (userNavigated) {
                    Log.i(TAG, "[PROGRESS] restore skipped: user navigated")
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

        // Coroutine cancellation can't interrupt blocking execute()/body reads —
        // sever the sockets so in-flight page streams end now. A request that
        // slips past the stopped check before registration runs to completion
        // harmlessly (its failure path is silenced by the stopped flag).
        inflightCalls.values.forEach { runCatching { it.cancel() } }
        inflightCalls.clear()
    }

    override fun getStartPage(): Int = startPageValue

    override fun putStartPage(page: Int) {
        if (page != startPageBaseline) {
            userNavigated = true
        }
        startPageValue = page

        // Persist locally for instant restore on next open
        saveReadingProgress(context, arcId, page)

        // Sync progress to LANraragi server (1-indexed), serialized and
        // conflated so the last page of a fast-flip burst — not an
        // arbitrary racing PUT — is what the server ends up with.
        progressSyncer.submit(page)
    }

    /**
     * Persist the local-only intra-page scroll fraction. Use the
     * app-wide IO scope: putScrollFraction may fire during
     * stop()/teardown when the provider scope is already cancelled,
     * and we still want the value on disk.
     */
    override fun putScrollFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        // Reject 0 (same guard as DirGalleryProvider): a 0 written on an early
        // exit — before the first fill publishes a real fraction, or before the
        // async restore applies — would otherwise clobber a previously saved
        // mid-page position back to the top.
        if (clamped == 0f) return
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                ServiceRegistry.dataModule.historyRepository
                    .setHistoryScrollFraction(arcId, serverProfileId, clamped)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save scroll fraction: ${e.message}")
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
            } catch (ignored: PageCancelledException) {
                // stop()/onCancelRequest severed the call deliberately — not a
                // failure. Stay quiet so a recycled tile doesn't show an error
                // placeholder; a re-request just downloads again.
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
        // Sever the in-flight HTTP call; the blocked read throws IOException,
        // which downloadPageToCache converts to PageCancelledException (quiet).
        inflightCalls[index]?.cancel()
    }

    override fun getError(): String = errorMessage ?: "Unknown error"

    // ==================== Internal ====================

    private fun getCacheFile(index: Int): File = File(cacheDir, "page_$index")

    // Per-page download mutexes (same-page exclusion across the user-request
    // and preload paths). Replaces mod-32 striped Java monitors: a stripe
    // collision serialized UNRELATED pages — a user-visible page could park,
    // uncancellably and pinning an IO thread, behind a preload's full network
    // download. Mutex.withLock keeps the wait suspending and cancellable.
    // Bounded by the archive's page count, entries live for the provider.
    private val pageMutexes = ConcurrentHashMap<Int, Mutex>()

    private fun pageMutex(index: Int): Mutex = pageMutexes.computeIfAbsent(index) { Mutex() }

    // Track in-flight page requests to avoid submitting duplicate tasks to the thread pool
    private val inflightRequests = ConcurrentHashMap<Int, Boolean>()

    // In-flight page HTTP calls, keyed by page index. Coroutine cancellation
    // cannot interrupt the blocking execute()/body read inside
    // ReaderPageCache.downloadToFile, so stop()/onCancelRequest sever these
    // sockets explicitly (NET-3).
    private val inflightCalls = ConcurrentHashMap<Int, Call>()

    /**
     * Download a page to cache if not already cached.
     * Thread-safe: a per-page [Mutex] prevents concurrent downloads of the same page.
     * Delegates the actual download + validation to [ReaderPageCache.downloadToFile].
     */
    @Throws(IOException::class)
    private suspend fun downloadPageToCache(
        index: Int,
        progressCallback: ReaderPageCache.ProgressCallback? = null
    ) {
        val cacheFile = getCacheFile(index)
        if (cacheFile.exists() && cacheFile.length() > ReaderPageCache.MIN_IMAGE_SIZE) {
            return // Already cached and sufficiently large
        }

        pageMutex(index).withLock {
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
            val pageUrl = resolvePageUrl(serverUrl, paths[index])
            val currentPageClient = pageClient
                ?: ServiceRegistry.networkModule.okHttpClient

            var call: Call? = null
            try {
                ReaderPageCache.downloadToFile(
                    currentPageClient, pageUrl, cacheFile, index, progressCallback,
                    onCallCreated = { c ->
                        call = c
                        inflightCalls[index] = c
                    }
                )
            } catch (e: IOException) {
                if (call?.isCanceled() == true || stateRef.get().stopped) {
                    throw PageCancelledException(e)
                }
                throw e
            } finally {
                inflightCalls.remove(index)
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun downloadAndDecodePage(index: Int) {
        // Try up to 2 times: once normally, once with cache invalidation
        for (attempt in 0 until 2) {
            var cacheFile = getCacheFile(index)

            // On retry, wait 1s for network recovery, then force re-download.
            // Suspending delay: parkNanos here blocked an IO-pool thread for
            // the full second AND ignored coroutine cancellation — leaving the
            // reader (provider scope cancel on exit) waiting on a parked
            // thread. delay() frees the thread and aborts on cancellation.
            if (attempt > 0) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Retry page $index (attempt ${attempt + 1}), waiting 1s...")
                }
                delay(RETRY_DELAY_MS)
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

            // Decode image. Routed through the bounded decoderDispatcher so
            // a burst of concurrent page requests doesn't blow past the
            // global cap on simultaneous BitmapFactory work.
            val image = withContext(ServiceRegistry.coroutineModule.decoderDispatcher) {
                FileInputStream(cacheFile).use { fis -> Image.decode(fis, false) }
            }
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
            for (i in 1..PRELOAD_COUNT) {
                if (stateRef.get().stopped) break
                val preloadIndex = currentIndex + i
                if (preloadIndex >= paths.size) break
                val cached = getCacheFile(preloadIndex)
                if (cached.exists() && cached.length() > ReaderPageCache.MIN_IMAGE_SIZE) continue

                launch {
                    preloadSemaphore.acquire()
                    try {
                        if (!stateRef.get().stopped) {
                            downloadPageToCache(preloadIndex)
                            Log.d(TAG, "Preloaded page $preloadIndex")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Preload failed for page $preloadIndex: ${e.message}")
                    } finally {
                        preloadSemaphore.release()
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
        private const val RETRY_DELAY_MS = 1_000L // 1 second retry delay (suspending)

        /**
         * Bound on how long the consumer waits for an in-flight warm
         * to land in the slot before falling through to the cold
         * decode path. 300ms covers a typical LRR decode (~280ms
         * including local-network bytes); a slow warm beyond that
         * is better skipped than waited on.
         */
        private const val WARM_AWAIT_MS: Long = 300L

        /** Bound on the start()-time metadata fetch for progress restore. */
        private const val METADATA_TIMEOUT_MS = 3_000L
    }
}

/**
 * Wraps an IOException caused by deliberate call cancellation
 * (stop()/onCancelRequest) so the request path can go quiet instead of
 * surfacing a page-error placeholder (NET-3).
 */
private class PageCancelledException(cause: IOException) : IOException(cause)
