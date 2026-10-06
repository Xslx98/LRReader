package com.lanraragi.reader.gallery

import android.util.Log
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.download.DownloadPageNaming
import com.lanraragi.reader.download.DownloadPageRepair
import com.lanraragi.reader.download.DurablePageWrite
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The reader's side of a **hybrid** session: an archive that has a download
 * row (in progress, paused, failed or pending) is read from — and written
 * to — its download directory under the worker's naming
 * ([DownloadPageNaming]) instead of the reader cache. Pages the worker has
 * already landed are served straight from disk; pages the reader fetches
 * itself are written there so the worker's "already on disk and valid →
 * skip" branch later picks them up. Both writers use unique `.tmp` +
 * fsync + rename ([DurablePageWrite]), so a page written by both sides just ends up with the same bytes
 * twice. Reader-written pages are never reported to the download progress
 * tracker (ADR-001 publishing channels); the worker counts them when its
 * window reaches them.
 *
 * Shared by the standalone reader ([LRRGalleryProvider]) and tank member
 * sources ([LrrTankMemberSource]) so the two never drift.
 *
 * @param downloadDir the archive's download directory (may not exist yet).
 * @param warmDir the archive's standalone reader cache dir, where the
 *   detail-screen warm-up may already have dropped `page_N` files.
 * @param durable the worker's write guarantees (fsync, free-space floor,
 *   size cap) applied to every page this store lands in [downloadDir]
 *   (audit REL-04); a test seam.
 * @param onRepairRequested called after a damaged page was handed to the
 *   download pipeline ([handOverDamagedPage]); production re-queues a
 *   finished download so the worker fetches the page again.
 */
internal class HybridPageStore(
    val downloadDir: File,
    private val warmDir: File,
    private val durable: DurablePageWrite = DurablePageWrite.Production,
    private val onRepairRequested: () -> Unit = {},
) {

    /**
     * Pages whose download-dir copy turned out damaged in this session
     * ([handOverDamagedPage]); they are read from the reader cache instead.
     */
    private val damaged: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /**
     * The write policy for [target]: [durable] when it is a page of the
     * download directory, null for anything else (the disposable reader
     * cache, used until the page list is known).
     */
    fun durableWriteFor(target: File): DurablePageWrite? =
        durable.takeIf { target.parentFile == downloadDir }

    /**
     * Where 0-based page [index] lives, or null until the server page path
     * (which carries the extension) is known — and null for a page handed
     * over as damaged, which the session then reads from the reader cache.
     */
    fun pageFile(index: Int, pagePath: String?): File? =
        if (index in damaged) null else pagePath?.let { DownloadPageNaming.pageFile(downloadDir, index, it) }

    /**
     * A page of [downloadDir] whose body is damaged (the decoder rejected a
     * format this device can decode) is not the reader's to delete or
     * replace (audit 2026-10-06d PERF-01). Hand it to the download pipeline
     * instead (audit 2026-10-06e P4-d): mark it for a re-download
     * ([DownloadPageRepair]) and read page [index] from the reader cache for
     * the rest of this session, so the caller can fetch it again there at
     * once. Returns false — nothing done — for a file outside [downloadDir]
     * or a page already handed over.
     */
    fun handOverDamagedPage(index: Int, file: File): Boolean {
        if (file.parentFile != downloadDir || !damaged.add(index)) return false
        if (!DownloadPageRepair.mark(file)) Log.w(TAG, "Damaged page $index could not be marked for re-download")
        onRepairRequested()
        return true
    }

    /**
     * The download directory may not exist yet (READ tapped right after
     * DOWNLOAD) — create it the way the worker does, `.nomedia` included,
     * so page writes have a parent and the gallery stays out of the media
     * scanner even if the worker never gets there.
     */
    fun ensureDir() {
        try {
            if (!downloadDir.isDirectory && !downloadDir.mkdirs()) {
                Log.w(TAG, "Hybrid download dir could not be created: $downloadDir")
                return
            }
            val noMedia = File(downloadDir, ".nomedia")
            if (!noMedia.exists()) noMedia.createNewFile()
        } catch (e: IOException) {
            Log.w(TAG, "Hybrid download dir setup failed: ${e.message}")
        }
    }

    /**
     * The open-helper warm-up ([ReaderPageCache.preloadForDetail]) may
     * already have fetched page [index] into the reader cache before the
     * session knew it would address the download directory. Move that copy
     * into [target] instead of fetching the bytes a second time. Returns
     * true when [target] is now populated. The copy gets the same
     * guarantees as a fetched page ([durableWriteFor]): it is fsynced
     * before the rename, and is not made when the volume is below the
     * free-space floor or the page is over the size cap (the caller then
     * fetches, which reports the condition).
     */
    fun adoptWarmCachedPage(index: Int, target: File): Boolean {
        val warm = File(warmDir, "page_$index")
        if (target.parentFile != downloadDir || !isPresent(warm)) return false
        if (!ReaderPageCache.validateImageFile(warm)) return false
        val parent = target.parentFile ?: return false
        val policy = durableWriteFor(target)
        if (policy != null && (!policy.hasRoom(parent) || warm.length() > policy.maxPageBytes)) return false
        val tmp = File(parent, "${target.name}.${Thread.currentThread().id}.tmp")
        return try {
            FileInputStream(warm).use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                    policy?.sync(output)
                }
            }
            if (tmp.renameTo(target)) {
                warm.delete()
                if (BuildConfig.DEBUG) Log.d(TAG, "Adopted warm-cached page $index into download dir")
                true
            } else {
                tmp.delete()
                false
            }
        } catch (e: IOException) {
            tmp.delete()
            Log.w(TAG, "Failed to adopt warm-cached page $index: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "HybridPageStore"
        private const val BUFFER_SIZE = 65536

        /** A page file counts as landed once it exists and is not a truncated stub. */
        fun isPresent(file: File): Boolean =
            file.exists() && file.length() > ReaderPageCache.MIN_IMAGE_SIZE
    }
}
