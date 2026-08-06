package com.hippo.ehviewer.gallery

import android.content.Context
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
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
import java.util.concurrent.ConcurrentHashMap

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
     */
    @Throws(IOException::class)
    suspend fun obtainImage(page0: Int, onPercent: ((Float) -> Unit)? = null): Image?

    /** Download-only warm of [page0] (no decode). Best-effort; never throws. */
    suspend fun prefetchPage(page0: Int)

    /** Copy [page0]'s bytes into [dest] for the save/share path. */
    fun savePage(page0: Int, dest: UniFile): Boolean

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
 */
internal class LrrTankMemberSource(
    context: Context,
    override val arcid: String,
    private val serverUrl: String,
    private val pageClient: OkHttpClient,
    private val listClient: OkHttpClient,
) : TankMemberSource {

    private val appContext = context.applicationContext

    @Volatile
    private var paths: Array<String>? = null

    @Volatile
    private var stopped = false

    private val listMutex = Mutex()
    private val pageMutexes = ConcurrentHashMap<Int, Mutex>()
    private val inflightCalls = ConcurrentHashMap<Int, Call>()

    private val cacheDir: File by lazy { ReaderPageCache.ensureCacheDir(appContext, arcid) }

    private fun cacheFile(page0: Int): File = File(cacheDir, "page_$page0")

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
        downloadPage(page0, onPercent)
        val file = cacheFile(page0)
        if (!file.exists() || file.length() < ReaderPageCache.MIN_IMAGE_SIZE) {
            throw IOException("Cached page $page0 missing or too small for $arcid")
        }
        val image = withContext(ServiceRegistry.coroutineModule.decoderDispatcher) {
            FileInputStream(file).use { fis -> Image.decode(fis, false) }
        }
        if (image == null) {
            // Corrupt bytes: drop them so a retry re-downloads.
            file.delete()
        }
        return image
    }

    override suspend fun prefetchPage(page0: Int) {
        try {
            downloadPage(page0, onPercent = null)
        } catch (e: IOException) {
            // Best-effort warm; the on-demand path retries with real errors.
        }
    }

    override fun savePage(page0: Int, dest: UniFile): Boolean {
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

    override fun cancelPage(page0: Int) {
        inflightCalls[page0]?.cancel()
    }

    override fun cancelAll() {
        stopped = true
        inflightCalls.values.forEach { runCatching { it.cancel() } }
        inflightCalls.clear()
    }

    /**
     * Download member-local [page0] into the shared cache file if absent.
     * Per-page mutex excludes the on-demand and prefetch paths from
     * double-downloading; in-flight calls are registered for severing.
     */
    @Throws(IOException::class)
    private suspend fun downloadPage(page0: Int, onPercent: ((Float) -> Unit)?) {
        val file = cacheFile(page0)
        if (file.exists() && file.length() > ReaderPageCache.MIN_IMAGE_SIZE) return
        val pagePaths = paths ?: throw IOException("Page list not loaded for $arcid")
        if (page0 < 0 || page0 >= pagePaths.size) {
            throw IOException("Page $page0 out of bounds (size=${pagePaths.size}) for $arcid")
        }
        pageMutexes.computeIfAbsent(page0) { Mutex() }.withLock {
            if (stopped) throw TankPageCancelledException(IOException("source stopped"))
            if (file.exists() && file.length() > ReaderPageCache.MIN_IMAGE_SIZE) return
            val url = resolvePageUrl(serverUrl, pagePaths[page0])
            var call: Call? = null
            try {
                ReaderPageCache.downloadToFile(
                    pageClient, url, file, page0,
                    progressCallback = onPercent?.let { cb ->
                        ReaderPageCache.ProgressCallback { _, fraction -> cb(fraction) }
                    },
                    onCallCreated = { c ->
                        call = c
                        inflightCalls[page0] = c
                    },
                )
            } catch (e: IOException) {
                if (call?.isCanceled() == true || stopped) throw TankPageCancelledException(e)
                throw e
            } finally {
                inflightCalls.remove(page0)
            }
        }
    }

    private companion object {
        const val SAVE_BUFFER_SIZE = 65536
    }
}
