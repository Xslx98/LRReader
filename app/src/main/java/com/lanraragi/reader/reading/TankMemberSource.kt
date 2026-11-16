package com.lanraragi.reader.reading

import android.content.Context
import android.os.Build
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.framework.lib.image.DecodeResult
import com.lanraragi.framework.lib.image.Image
import com.lanraragi.reader.storage.StorageFile
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LrrFileListCache
import com.lanraragi.reader.client.api.resolvePageUrl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Per-member page sourcing behind [TankGalleryProvider]: the composite
 * reader routes a global page to its member's source, which fetches +
 * decodes MEMBER-LOCAL 0-indexed pages. Implementations exist for the two
 * routes the standalone reader already has — LANraragi streaming
 * ([LrrTankMemberSource]) and a locally downloaded dir
 * ([DirTankMemberSource], commit c4).
 *
 * Threading: all suspend members are IO-safe (called from the provider's
 * IO scope); [cancelPage]/[cancelAll] and [savePage] may be called from
 * any thread.
 */
internal interface TankMemberSource {

    val arcid: String

    /**
     * Load (or return the already-loaded) page list and return the REAL
     * page count. Idempotent; concurrent callers coalesce.
     * @throws IOException when the list cannot be obtained.
     */
    @Throws(IOException::class)
    suspend fun ensurePageCount(): Int

    /** Already-known real page count without any IO, or null before [ensurePageCount]. */
    fun knownPageCount(): Int?

    /**
     * Fetch (if needed) and decode member-local [page0]. Null = the bytes
     * arrived but did not decode (corrupt page). [onPercent] reports
     * download progress in 0..1 when the bytes come off the network.
     * @throws IOException on fetch failure — [TankPageCancelledException]
     *   when the failure is a deliberate cancel (quiet path).
     * @throws PageDecodeException when a source can say WHY the page did not
     *   decode (unsupported format, too large, damaged) instead of null.
     */
    @Throws(IOException::class)
    suspend fun obtainImage(page0: Int, onPercent: ((Float) -> Unit)? = null): Image?

    /** Download-only warm of [page0] (no decode). Best-effort; never throws. */
    suspend fun prefetchPage(page0: Int)

    /** Copy [page0]'s bytes into [dest] for the save/share path. */
    fun savePage(page0: Int, dest: StorageFile): Boolean

    /** [page0]'s file when it is already on disk, for a header-only size read ([PageAspectProbe]); no IO otherwise. */
    fun openPageOnDisk(page0: Int): InputStream?

    /** Sever any in-flight network work for [page0]. */
    fun cancelPage(page0: Int)

    /** Sever all in-flight network work (session stop). */
    fun cancelAll()
}

/**
 * Deliberate-cancel marker (stop() / onCancelRequest severed the call) so
 * the provider's request path can go quiet instead of rendering an error
 * page. Mirrors LRRGalleryProvider's private PageCancelledException.
 */
internal class TankPageCancelledException(cause: IOException) : IOException(cause)

/**
 * Streaming member source: file list via [LrrFileListCache] /
 * [LRRArchiveApi.getFileList], page bytes via [ReaderPageCache.downloadToFile]
 * into the member's OWN standalone reader cache dir — so bytes cached by a
 * tank session serve a later standalone open of the same archive and vice
 * versa.
 *
 * **Hybrid mode** (non-null [store]): the member has a download row, so
 * pages are resolved, read and written in its download directory through
 * the shared [HybridPageStore] — exactly what the standalone reader does
 * for a tracked download — and warm reader-cache pages are adopted there.
 */
internal class LrrTankMemberSource(
    context: Context,
    override val arcid: String,
    private val serverUrl: String,
    private val pageClient: OkHttpClient,
    private val listClient: OkHttpClient,
    /** Non-null in hybrid mode; exposed for routing tests. */
    val store: HybridPageStore? = null,
) : TankMemberSource {

    private val appContext = context.applicationContext

    @Volatile
    private var paths: Array<String>? = null

    @Volatile
    private var stopped = false

    private val listMutex = Mutex()

    /** Per-page fetch mutexes and the in-flight calls cancelPage / cancelAll sever. */
    private val jobs = PageJobs()

    private val cacheDir: File by lazy { ReaderPageCache.ensureCacheDir(appContext, arcid) }

    /** Hybrid mode: the download dir is created (with `.nomedia`) once, before the first write. */
    private val storeDirReady: Boolean by lazy { store?.ensureDir(); true }

    private fun cacheFile(page0: Int): File =
        store?.pageFile(page0, paths?.getOrNull(page0)) ?: File(cacheDir, "page_$page0")

    override fun knownPageCount(): Int? = paths?.size

    override suspend fun ensurePageCount(): Int {
        paths?.let { return it.size }
        listMutex.withLock {
            paths?.let { return it.size }
            val fetched = LrrFileListCache.get(serverUrl, arcid) ?: run {
                val fromServer = LRRArchiveApi.getFileList(listClient, serverUrl, arcid)
                LrrFileListCache.put(serverUrl, arcid, fromServer)
                fromServer
            }
            paths = fetched
            return fetched.size
        }
    }

    override suspend fun obtainImage(page0: Int, onPercent: ((Float) -> Unit)?): Image? {
        // One file for this attempt (see PageLoadLoop): fetch and decode agree on it.
        val file = cacheFile(page0)
        downloadPage(page0, file, onPercent)
        if (!LocalPage.probe(file)) {
            throw IOException("Cached page $page0 missing or too small for $arcid")
        }
        val decoded = withContext(ServiceRegistry.coroutineModule.decoderDispatcher) {
            FileInputStream(file).use { fis -> Image.decodeResult(fis, false) }
        }
        if (decoded is DecodeResult.Ok) return decoded.value
        // Audit 2026-10-06d PERF-01: only damaged bytes in the reader cache
        // are dropped so a retry re-downloads. A valid page this device
        // cannot decode is kept (a re-download brings the same bytes), and a
        // download-dir page (hybrid) belongs to the download worker: a
        // damaged one is handed to it for a re-download and this session
        // reads a fresh copy from the reader cache instead (audit 2026-10-06e
        // P4-d). The hand-over happens once per page, so this recurses once.
        val failure = PageFailure.ofDecode(decoded, ReaderPageCache.detectImageFormat(file), Build.VERSION.SDK_INT)
        if (failure is PageFailure.Corrupt) {
            if (store?.handOverDamagedPage(page0, file) == true) return obtainImage(page0, onPercent)
            if (file.parentFile != store?.downloadDir) file.delete()
        }
        throw PageDecodeException(failure)
    }

    override suspend fun prefetchPage(page0: Int) {
        try {
            downloadPage(page0, cacheFile(page0), onPercent = null)
        } catch (e: IOException) {
            // Best-effort warm; the on-demand path retries with real errors.
        }
    }

    override fun savePage(page0: Int, dest: StorageFile): Boolean {
        val cached = cacheFile(page0)
        if (!cached.exists()) return false
        return try {
            FileInputStream(cached).use { fis ->
                dest.openOutputStream().use { os -> fis.copyTo(os, SAVE_BUFFER_SIZE) }
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    override fun openPageOnDisk(page0: Int): InputStream? =
        cacheFile(page0).takeIf(LocalPage::probe)?.let(::FileInputStream)

    override fun cancelPage(page0: Int) {
        jobs.cancelCall(page0)
    }

    override fun cancelAll() {
        stopped = true
        jobs.clear()
    }

    /**
     * Download member-local [page0] into the shared cache file if absent.
     * Per-page mutex excludes the on-demand and prefetch paths from
     * double-downloading; in-flight calls are registered for severing.
     */
    @Throws(IOException::class)
    private suspend fun downloadPage(page0: Int, file: File, onPercent: ((Float) -> Unit)?) {
        if (LocalPage.probe(file)) return
        val pagePath = pagePathOf(page0)
        jobs.mutex(page0).withLock {
            if (stopped) throw TankPageCancelledException(IOException("source stopped"))
            if (LocalPage.probe(file)) return
            if (store != null) {
                check(storeDirReady)
                if (store.adoptWarmCachedPage(page0, file)) return
            }
            val url = resolvePageUrl(serverUrl, pagePath)
            var call: Call? = null
            try {
                ReaderPageCache.downloadToFile(
                    pageClient, url, file, page0,
                    progressCallback = onPercent?.let { cb ->
                        ReaderPageCache.ProgressCallback { _, fraction -> cb(fraction) }
                    },
                    onCallCreated = { c ->
                        call = c
                        jobs.track(page0, c)
                    },
                    durable = store?.durableWriteFor(file),
                )
            } catch (e: IOException) {
                throw if (call?.isCanceled() == true || stopped) TankPageCancelledException(e) else e
            } finally {
                jobs.untrack(page0)
            }
        }
    }

    /** The server path of member-local [page0]; throws while the page list is unknown or out of range. */
    @Throws(IOException::class)
    private fun pagePathOf(page0: Int): String {
        val pagePaths = paths ?: throw IOException("Page list not loaded for $arcid")
        return pagePaths.getOrNull(page0)
            ?: throw IOException("Page $page0 out of bounds (size=${pagePaths.size}) for $arcid")
    }

    private companion object {
        const val SAVE_BUFFER_SIZE = 65536
    }
}
