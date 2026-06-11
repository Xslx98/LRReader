package com.hippo.ehviewer.gallery

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.module.Cacheable
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LrrFileListCache
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
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
 *
 * Also owns a single-entry **decoded-page slot** ([consumeDecodedPage] /
 * private `storeDecodedSlot`): a cross-session in-memory hand-off for a
 * freshly decoded page from the detail-screen warmup to the reader that
 * will open next. Bounded to one entry with a 10-second TTL — the slot
 * is the bridge across an Activity launch, not a general image cache.
 */
object ReaderPageCache : Cacheable {

    private const val TAG = "ReaderPageCache"
    private const val CACHE_PARENT = "lrr_pages"
    private const val BUFFER_SIZE = 65536 // 64KB for LAN transfers
    private const val SP_CACHE_ACCESS = "lrr_cache_access"
    const val MIN_IMAGE_SIZE = 1024L // 1KB — below this a file is likely corrupt
    const val MAX_TOTAL_CACHE_BYTES = 500L * 1024L * 1024L // 500MB total limit
    private const val DETAIL_PRELOAD_RADIUS = 1 // Pages before and after the progress page

    /**
     * Major brands of ISO-BMFF image containers the platform decoder can read:
     * AVIF (API 31+) plus HEIF/HEIC (API 28+ = minSdk). All share the "ftyp"
     * box at bytes 4-7 with a 4-char brand at bytes 8-11. Kept in sync with
     * [GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS] (.avif/.heif/.heic) and the
     * download worker's copy in [com.hippo.ehviewer.download.LRRDownloadWorker].
     */
    private val ISO_BMFF_IMAGE_BRANDS = setOf(
        "avif", "avis", // AV1 Image File Format (still / sequence)
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", // HEVC-coded
        "mif1", "msf1", // generic HEIF (image / sequence)
    )

    /**
     * TTL for the decoded-page slot. Long enough to bridge a typical
     * "tap read → reader Activity visible" transition; short enough
     * that a user who backs out of the detail page doesn't leave a
     * decoded image (10–30 MB) sitting in memory indefinitely.
     */
    private const val DECODED_SLOT_TTL_MS = 10_000L

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
            .edit {
                putLong(arcId, System.currentTimeMillis())
            }

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

                // ISO-BMFF (AVIF / HEIF / HEIC): "ftyp" at bytes 4-7, 4-char
                // major brand at bytes 8-11. Accept any brand the platform
                // decoder can read so HEIC/HEIF pages — listed as supported
                // page files — are not rejected as "not an image".
                if (read >= 12 &&
                    header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() &&
                    String(header, 8, 4, Charsets.US_ASCII) in ISO_BMFF_IMAGE_BRANDS
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

        sp.edit {
            for (dir in dirList) {
                if (totalSize <= maxTotalBytes) break
                val dirSize = getDirSize(dir)
                deleteDir(dir)
                remove(dir.name)
                totalSize -= dirSize
                Log.d(TAG, "Evicted cache: ${dir.name} (${dirSize / 1024} KB)")
            }
        }
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

    // ---- Decoded-page slot ----

    private data class DecodedSlot(
        val arcid: String,
        val pageIndex: Int,
        val image: Image,
        val expiresAt: Long,
    )

    private val slotLock = Any()

    /**
     * Single-entry cross-session decoded-page slot. Producer side is
     * the detail-page warmup ([preloadForDetail], and Dir-equivalent
     * paths added later). Consumer is the gallery provider's start()
     * — see [consumeDecodedPage]. Guarded by [slotLock].
     */
    private var decodedSlot: DecodedSlot? = null

    /**
     * In-flight warm jobs keyed by arcid. Lets consumers briefly
     * await a same-arcid warm that started just before they did
     * (the openHelper trigger fires ~70-150ms before the provider's
     * own consumeDecodedPage call, racing the warm's store). One
     * entry per arcid; entries auto-remove themselves on completion
     * via [Job.invokeOnCompletion].
     */
    private val activeWarmups: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    /**
     * Atomically take ownership of a previously-warmed decoded
     * Image. Returns null if there is no slot, the (arcid,
     * pageIndex) doesn't match, or the slot has aged past
     * [DECODED_SLOT_TTL_MS]. On a hit, the caller is responsible
     * for the eventual `image.recycle()` (typically by handing the
     * Image to `GalleryProvider.notifyPageSucceed(index, image)`,
     * which wraps it in an ImageWrapper that the existing pipeline
     * recycles when refcount drops to zero).
     */
    /**
     * Consume the decoded slot for [arcid]:[pageIndex]. Returns null
     * if the slot is empty, expired, or holds a different
     * (arcid, pageIndex).
     *
     * If [awaitInflightWarmMs] > 0 and a warm targeting [arcid] is
     * still running, this awaits its completion (bounded) before
     * checking the slot. This bridges the openHelper-trigger race
     * where warm and provider start fire concurrently and the
     * provider would otherwise consume before warm has stored.
     * 300ms is the recommended default for Dir/LRR providers — long
     * enough to cover a typical decode (~130-280ms) without making
     * a slow warm worse than skipping it.
     *
     * Order of slot-state checks (intentional): empty → expired →
     * mismatch. Expired is checked before mismatch so a stale slot
     * for a different arcid still gets recycled.
     */
    suspend fun consumeDecodedPage(
        arcid: String,
        pageIndex: Int,
        awaitInflightWarmMs: Long = 0L,
    ): Image? {
        if (awaitInflightWarmMs > 0L) {
            val warmJob = activeWarmups[arcid]
            if (warmJob != null && warmJob.isActive) {
                Log.i(TAG, "[WARM] consume awaiting in-flight warm arcid=$arcid timeoutMs=$awaitInflightWarmMs")
                val waitStart = System.currentTimeMillis()
                try {
                    withTimeout(awaitInflightWarmMs) { warmJob.join() }
                    Log.i(TAG, "[WARM] consume await completed arcid=$arcid waitedMs=${System.currentTimeMillis() - waitStart}")
                } catch (e: TimeoutCancellationException) {
                    Log.i(TAG, "[WARM] consume await timed out arcid=$arcid waitedMs=${System.currentTimeMillis() - waitStart}")
                }
            }
        }
        synchronized(slotLock) {
            val slot = decodedSlot
            if (slot == null) {
                Log.i(TAG, "[WARM] consume MISS empty arcid=$arcid page=$pageIndex")
                return null
            }
            if (System.currentTimeMillis() > slot.expiresAt) {
                Log.i(
                    TAG,
                    "[WARM] consume MISS expired arcid=$arcid page=$pageIndex " +
                        "have=(${slot.arcid},${slot.pageIndex})"
                )
                slot.image.recycle()
                decodedSlot = null
                return null
            }
            if (slot.arcid != arcid || slot.pageIndex != pageIndex) {
                Log.i(
                    TAG,
                    "[WARM] consume MISS mismatch want=($arcid,$pageIndex) " +
                        "have=(${slot.arcid},${slot.pageIndex})"
                )
                return null
            }
            val ageMs = System.currentTimeMillis() - (slot.expiresAt - DECODED_SLOT_TTL_MS)
            Log.i(TAG, "[WARM] consume HIT arcid=$arcid page=$pageIndex ageMs=$ageMs")
            decodedSlot = null
            return slot.image
        }
    }

    /**
     * Park [image] for [arcid]:[pageIndex]. Replaces (and recycles)
     * any previously-stored slot — there is intentionally only one
     * slot to bound memory.
     */
    private fun storeDecodedSlot(arcid: String, pageIndex: Int, image: Image) {
        synchronized(slotLock) {
            val replaced = decodedSlot != null
            decodedSlot?.image?.recycle()
            decodedSlot = DecodedSlot(
                arcid, pageIndex, image,
                System.currentTimeMillis() + DECODED_SLOT_TTL_MS,
            )
            Log.i(TAG, "[WARM] store arcid=$arcid page=$pageIndex replaced=$replaced ttlMs=$DECODED_SLOT_TTL_MS")
        }
    }

    /**
     * [Cacheable] hook — called on profile switch and on global
     * cache-clear. Drops the in-memory slot so a stale decoded page
     * from the previous server profile cannot leak into a reader
     * opened against a different host.
     */
    override fun clearCache() {
        synchronized(slotLock) {
            decodedSlot?.image?.recycle()
            decodedSlot = null
        }
    }

    // ---- Directory-source warmup ----

    /**
     * Decode-warm the start page of a downloaded archive into the
     * [decodedSlot] slot, in parallel with the user tapping "open
     * reader". By the time `DirGalleryProvider.start()` finishes
     * enumerating files and reaches its consume call, the start page
     * is already a decoded `Image` waiting to be handed to the
     * pipeline.
     *
     * The "start page" here is the locally-stored 0-indexed reading
     * progress for [arcId] — the same value `DirGalleryProvider`
     * eventually settles on after the metadata fetch, modulo a
     * server-progress override that may bump it. If the override
     * picks a different page the slot simply won't match on consume
     * and the regular decode path runs.
     */
    fun warmDir(
        context: Context,
        arcId: String,
        dir: UniFile,
        startPageOverride: Int = -1,
    ): Job {
        // CoroutineStart.LAZY so we can publish the Job into
        // activeWarmups *before* it begins running — otherwise a
        // provider racing the launch could observe activeWarmups[arcId]
        // == null and miss the await window.
        val job = ServiceRegistry.coroutineModule.ioScope.launch(start = CoroutineStart.LAZY) {
            val startMs = System.currentTimeMillis()
            Log.i(TAG, "[WARM] warmDir begin arcid=$arcId")
            try {
                val files = runInterruptible(Dispatchers.IO) {
                    DirImageFiles.listSorted(dir)
                }
                if (files == null) {
                    Log.i(TAG, "[WARM] warmDir abort listFiles=null arcid=$arcId")
                    return@launch
                }
                if (files.isEmpty()) {
                    Log.i(TAG, "[WARM] warmDir abort empty-dir arcid=$arcId")
                    return@launch
                }
                val pageIndex = (
                    if (startPageOverride >= 0) {
                        startPageOverride
                    } else {
                        GalleryProvider2.loadReadingProgress(context.applicationContext, arcId)
                    }
                    ).coerceIn(0, files.size - 1)
                val image = withContext(
                    ServiceRegistry.coroutineModule.decoderDispatcher
                ) {
                    DirImageFiles.decode(context.applicationContext, files[pageIndex])
                }
                if (image != null) {
                    storeDecodedSlot(arcId, pageIndex, image)
                    Log.i(
                        TAG,
                        "[WARM] warmDir done arcid=$arcId page=$pageIndex " +
                            "elapsedMs=${System.currentTimeMillis() - startMs}"
                    )
                } else {
                    Log.i(TAG, "[WARM] warmDir decode null arcid=$arcId page=$pageIndex")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[WARM] warmDir failed for $arcId: ${e.message}")
            }
        }
        registerActiveWarmup(arcId, job)
        job.start()
        return job
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
    ): Job {
        // CoroutineStart.LAZY so the Job is registered in
        // activeWarmups before it begins running. See [warmDir] for
        // the same pattern.
        val job = ServiceRegistry.coroutineModule.ioScope.launch(start = CoroutineStart.LAZY) {
            try {
            val appContext = context.applicationContext
            ensureCacheDir(appContext, arcId)

            val client = ServiceRegistry.networkModule.longReadClient
            // Reuse the file list cache so a re-entry to the same detail
            // page within the cache TTL avoids the /files round-trip.
            val pages = LrrFileListCache.get(arcId) ?: run {
                val fetched = LRRArchiveApi.getFileList(client, serverUrl, arcId)
                LrrFileListCache.put(arcId, fetched)
                fetched
            }
            if (pages.isEmpty()) return@launch

            val pageClient = ServiceRegistry.networkModule.okHttpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                // Disable the shared 30s callTimeout so a large page body isn't aborted
                // mid-transfer; readTimeout still catches genuine stalls.
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val start = (centerPage - DETAIL_PRELOAD_RADIUS).coerceAtLeast(0)
            val end = (centerPage + DETAIL_PRELOAD_RADIUS).coerceAtMost(pages.size - 1)

            for (pageIndex in start..end) {
                val cacheFile = getCacheFile(appContext, arcId, pageIndex)
                if (!(cacheFile.exists() && cacheFile.length() > MIN_IMAGE_SIZE)) {
                    try {
                        val pageUrl = serverUrl + pages[pageIndex]
                        downloadToFile(pageClient, pageUrl, cacheFile, pageIndex)
                        Log.d(TAG, "Detail preloaded page $pageIndex for $arcId")
                    } catch (e: Exception) {
                        Log.d(TAG, "Detail preload page $pageIndex failed: ${e.message}")
                        continue
                    }
                }

                // Decode-warm the *center* page only: it's the one the
                // reader will display first, and decoding its neighbours
                // would bloat memory before they're actually visible.
                if (pageIndex == centerPage) {
                    val decodeStart = System.currentTimeMillis()
                    try {
                        val decoded = withContext(
                            ServiceRegistry.coroutineModule.decoderDispatcher
                        ) {
                            FileInputStream(cacheFile).use { fis ->
                                Image.decode(fis, false)
                            }
                        }
                        if (decoded != null) {
                            storeDecodedSlot(arcId, centerPage, decoded)
                            Log.i(
                                TAG,
                                "[WARM] preloadForDetail done arcid=$arcId page=$centerPage " +
                                    "decodeMs=${System.currentTimeMillis() - decodeStart}"
                            )
                        } else {
                            Log.i(TAG, "[WARM] preloadForDetail decode null arcid=$arcId page=$centerPage")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[WARM] preloadForDetail decode-warm $centerPage failed: ${e.message}")
                    }
                }
            }

            cleanupOldCaches(appContext)
            } catch (e: Exception) {
                Log.d(TAG, "Detail preload failed for $arcId: ${e.message}")
            }
        }
        registerActiveWarmup(arcId, job)
        job.start()
        return job
    }

    /**
     * Publish a warm-up [Job] under [arcid] in [activeWarmups] so
     * concurrent consumers can briefly await its completion. The job
     * removes itself on finish (or cancel) — using `remove(key,
     * value)` so a later same-arcid warm that replaced this entry
     * isn't blown away.
     */
    private fun registerActiveWarmup(arcid: String, job: Job) {
        activeWarmups[arcid] = job
        job.invokeOnCompletion { activeWarmups.remove(arcid, job) }
    }
}
