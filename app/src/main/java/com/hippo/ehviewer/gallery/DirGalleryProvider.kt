/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.gallery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hippo.ehviewer.GetText
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.runSuspend
import com.hippo.lib.glgallery.GalleryPageView
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.IOUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class DirGalleryProvider : GalleryProvider2 {

    private val dir: UniFile
    private val context: Context?
    private val arcId: String?
    private val serverUrl: String?
    private var startPageValue: Int = 0

    private val fileList = AtomicReference<Array<UniFile>?>()

    /**
     * Decode request queue with priority semantics:
     * - [PRIO_CURRENT] = 0  (page actively requested by the GalleryView)
     * - [PRIO_PRELOAD] = 1  (neighbour pages queued by the worker after a
     *   successful decode, to amortize the next scroll)
     *
     * Lower numeric priority dequeues first; ties broken by FIFO via
     * [seqCounter] so requests within the same tier come out in arrival
     * order.
     */
    private val requestQueue = PriorityBlockingQueue<PageRequest>()
    private val seqCounter = AtomicLong(0L)

    /**
     * Tracks which indices are currently in [requestQueue] *or* being
     * decoded by a worker, to skip duplicate enqueues while still
     * letting completed requests be re-issued (e.g. after cancel +
     * re-request from the GalleryView).
     */
    private val pendingIndices: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var providerScope: CoroutineScope? = null

    @Volatile
    private var sizeValue: Int = STATE_WAIT
    private var errorMessage: String? = null

    /**
     * False until the first [start] cycle has either applied a stored
     * scroll fraction or decided there is none worth restoring. While
     * false, [putScrollFraction] suppresses writes that would clobber
     * the stored fraction with `0` — the GalleryView's first
     * onUpdateCurrentIndex(0) callback fires almost instantly for a
     * downloaded archive (local file enum is synchronous), and without
     * this guard the saved fraction is overwritten before the async
     * metadata + DB read in [start] has a chance to consume it.
     *
     * Online (LRRGalleryProvider) avoids the race because its file list
     * round-trip is slow enough that the parallel-async fraction read
     * always lands first; we add the guard here for symmetry and to
     * cover any future scenario where the file list arrives quickly.
     */
    private val initialRestoreCompleted = AtomicBoolean(false)

    /** Legacy constructor (no progress tracking). */
    constructor(dir: UniFile) {
        this.dir = dir
        this.context = null
        this.arcId = null
        this.serverUrl = null
    }

    /** Constructor with Context and arcid for reading progress persistence. */
    constructor(dir: UniFile, context: Context, arcid: String) {
        this.dir = dir
        this.context = context.applicationContext
        this.arcId = arcid
        this.serverUrl = LRRAuthManager.getServerUrl()
        val ctx = this.context ?: return
        this.startPageValue = loadReadingProgress(ctx, arcid)
    }

    override fun getStartPage(): Int = startPageValue

    override fun putStartPage(page: Int) {
        startPageValue = page
        if (context != null && arcId != null) {
            saveReadingProgress(context, arcId!!, page)
        }
        // Sync progress to LANraragi server (1-indexed)
        if (arcId != null && serverUrl != null) {
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
    }

    override fun putScrollFraction(fraction: Float) {
        val targetArcid = arcId ?: return
        val clamped = fraction.coerceIn(0f, 1f)
        // Suppress fraction = 0 writes during the initial restore window:
        // the very first onUpdateCurrentIndex(0) callback fires before
        // [start]'s async metadata + DB read has consumed the stored
        // value, and without this guard the cold-open fraction of 0
        // overwrites the user's saved progress. Non-zero writes always
        // pass (the user has actually scrolled).
        if (clamped == 0f && !initialRestoreCompleted.get()) {
            return
        }
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                ServiceRegistry.dataModule.historyRepository
                    .setHistoryScrollFraction(targetArcid, clamped)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save scroll fraction: ${e.message}")
            }
        }
    }

    override fun start() {
        super.start()

        // Reset the save gate for this start cycle. stop() may have
        // flipped it earlier; we want each fresh open to re-do the
        // initial restore window.
        initialRestoreCompleted.set(false)

        // SupervisorJob so a single failed worker doesn't tear down the
        // sibling worker(s). Anchored on Dispatchers.IO; the actual decode
        // work hops onto decoderDispatcher inside processRequest below.
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                ServiceRegistry.coroutineModule.exceptionHandler
        )
        providerScope = scope

        // Async: load server progress + local intra-page scroll fraction
        // and jump if newer / non-zero. The fraction read runs in
        // parallel with the metadata fetch so its value is captured
        // before any onUpdateCurrentIndex(0) callback can clobber the
        // stored row — see the [initialRestoreCompleted] KDoc above.
        if (arcId != null && context != null) {
            scope.launch {
                // Kick off both reads in parallel. fractionDeferred is
                // a fast local DB hit; metadataDeferred is a network
                // round-trip and may fail / be skipped offline.
                val fractionDeferred = async {
                    try {
                        ServiceRegistry.dataModule.historyRepository
                            .getHistoryScrollFraction(arcId!!) ?: 0f
                    } catch (e: Exception) {
                        Log.w(TAG, "[PROGRESS] Failed to load scroll fraction: ${e.message}")
                        0f
                    }.coerceIn(0f, 1f)
                }
                val metadataDeferred = async {
                    if (serverUrl != null) {
                        try {
                            val client = ServiceRegistry.networkModule.okHttpClient
                            runSuspend { LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcId) }
                        } catch (e: Exception) {
                            Log.w(TAG, "[PROGRESS] Failed to load server metadata: ${e.message}")
                            null
                        }
                    } else {
                        null
                    }
                }
                try {
                    val savedFraction = fractionDeferred.await()
                    val metadata = metadataDeferred.await()
                    val serverProgress = metadata?.progress ?: 0
                    val serverTs = metadata?.lastreadtime ?: 0L
                    Log.i(TAG, "[PROGRESS] Server metadata: progress=$serverProgress" +
                            " lastreadtime=$serverTs savedFraction=$savedFraction")
                    if (serverProgress > 0 || savedFraction > 0f) {
                        val serverPage0 = (serverProgress - 1).coerceAtLeast(0)
                        val localTs = if (arcId != null) loadReadingTimestamp(context, arcId!!) else 0L
                        Log.i(TAG, "[PROGRESS] serverPage0=$serverPage0" +
                                " serverTs=$serverTs localTs=$localTs" +
                                " localPage=$startPageValue")
                        val resolvedPage: Int
                        if (serverProgress <= 0) {
                            // No server progress (offline, missing metadata, or
                            // genuinely page 0). Stick with the local SP-saved
                            // page and let the intra-page fraction restore on it.
                            resolvedPage = startPageValue
                        } else if (serverTs > localTs) {
                            resolvedPage = serverPage0
                            startPageValue = serverPage0
                            if (arcId != null) saveReadingProgress(context, arcId!!, serverPage0)
                            Log.i(TAG, "[PROGRESS] Using SERVER progress: page $serverPage0")
                        } else if (localTs > serverTs && startPageValue > 0) {
                            resolvedPage = startPageValue
                            Log.i(TAG, "[PROGRESS] Using LOCAL progress: page $startPageValue")
                        } else {
                            resolvedPage = max(serverPage0, startPageValue)
                            startPageValue = resolvedPage
                            Log.i(TAG, "[PROGRESS] Timestamps equal, using max: page $resolvedPage")
                        }
                        // Jump GalleryView if needed (page jump and/or
                        // intra-page fraction restore).
                        if (resolvedPage > 0 || savedFraction > 0f) {
                            val gv = galleryView
                            if (gv != null) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    try {
                                        val gvNow = galleryView ?: return@postDelayed
                                        if (savedFraction > 0f) {
                                            gvNow.setCurrentPageScrollFraction(resolvedPage, savedFraction)
                                            Log.i(TAG, "[PROGRESS] setCurrentPageScrollFraction(" +
                                                    "$resolvedPage, $savedFraction) called")
                                        } else {
                                            gvNow.setCurrentPage(resolvedPage)
                                            Log.i(TAG, "[PROGRESS] setCurrentPage($resolvedPage) called")
                                        }
                                    } finally {
                                        // Open the save gate after the restore
                                        // call lands. Subsequent putScrollFraction
                                        // writes (including 0 from a user-driven
                                        // scroll back to top) are now persisted.
                                        initialRestoreCompleted.set(true)
                                    }
                                }, 300)
                            } else {
                                initialRestoreCompleted.set(true)
                            }
                        } else {
                            // Nothing to restore. Open the save gate so
                            // future user scrolls can persist 0 if needed.
                            initialRestoreCompleted.set(true)
                        }
                    } else {
                        // No saved fraction and no server progress — open
                        // the gate immediately.
                        initialRestoreCompleted.set(true)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[PROGRESS] Restore flow failed: ${e.message}")
                    initialRestoreCompleted.set(true)
                }
            }
        } else {
            // Provider was constructed without arcid/context — no save
            // path is possible anyway, but flip the gate for symmetry.
            initialRestoreCompleted.set(true)
        }

        // Build the file list, then spin up the decode workers. We do
        // both in a single launched coroutine so workers don't start
        // looking at fileList before it's published.
        scope.launch {
            val files = try {
                runInterruptible(Dispatchers.IO) { DirImageFiles.listSorted(dir) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listFiles failed: ${e.message}")
                null
            }

            if (files == null) {
                sizeValue = STATE_ERROR
                errorMessage = GetText.getString(R.string.error_not_folder_path)
                notifyDataChanged()
                Log.i(TAG, "ImageDecoder end with error")
                return@launch
            }

            fileList.lazySet(files)
            sizeValue = files.size
            notifyDataChanged()

            // Cross-session decoded slot (filled by the detail-page
            // warmup or the open-helper warm trigger). On a hit we
            // bypass the per-page decode for the start page and the
            // user sees the page on the very next frame.
            // awaitInflightWarmMs bridges the openHelper-trigger
            // race: when warm and provider start race in parallel
            // (the openHelper trigger fires ~70-150ms before this
            // consume call but warm needs ~130ms to complete), a
            // brief wait turns the otherwise-MISS into a HIT.
            val targetArcid = arcId
            if (targetArcid != null) {
                val warmIndex = startPageValue.coerceIn(0, files.size - 1)
                val warmed = ReaderPageCache.consumeDecodedPage(
                    targetArcid, warmIndex, awaitInflightWarmMs = WARM_AWAIT_MS
                )
                if (warmed != null) {
                    Log.i(TAG, "[PROGRESS] decoded slot HIT for page=$warmIndex")
                    notifyPageSucceed(warmIndex, warmed)
                }
            }

            startDecodeWorkers(scope, files.size)
        }
    }

    override fun stop() {
        super.stop()
        providerScope?.cancel()
        providerScope = null
        requestQueue.clear()
        pendingIndices.clear()
        fileList.lazySet(null)
        Log.i(TAG, "ImageDecoder end")
    }

    override fun size(): Int = sizeValue

    override fun onRequest(index: Int) {
        enqueueRequest(index, PRIO_CURRENT)
        notifyPageWait(index)
    }

    override fun onForceRequest(index: Int) {
        // No on-disk cache to invalidate for a directory — re-enqueue at
        // current priority and the worker will redecode.
        enqueueRequest(index, PRIO_CURRENT)
    }

    override fun onCancelRequest(index: Int) {
        // Drop a queued (not-yet-decoding) request. A worker that has
        // already started decoding [index] will finish and report; the
        // GalleryView discards results for unbound pages.
        if (requestQueue.removeIf { it.index == index }) {
            pendingIndices.remove(index)
        }
    }

    override fun getError(): String? = errorMessage

    override fun getImageFilename(index: Int): String {
        // LEGACY: local files use index as filename fallback
        return index.toString()
    }

    override fun save(index: Int, file: UniFile): Boolean {
        val files = fileList.get() ?: return false
        if (index < 0 || index >= files.size) {
            return false
        }
        var inputStream: java.io.InputStream? = null
        var outputStream: java.io.OutputStream? = null
        return try {
            inputStream = files[index].openInputStream()
            outputStream = file.openOutputStream()
            IOUtils.copy(inputStream, outputStream)
            true
        } catch (e: IOException) {
            false
        } finally {
            IOUtils.closeQuietly(inputStream)
            IOUtils.closeQuietly(outputStream)
        }
    }

    override fun save(index: Int, dir: UniFile, filename: String): UniFile? {
        val files = fileList.get() ?: return null
        if (index < 0 || index >= files.size) {
            return null
        }
        val src = files[index]
        val extension = FileUtils.getExtensionFromFilename(src.name)
        val dst = dir.subFile(if (extension != null) "$filename.$extension" else filename)
                ?: return null
        var inputStream: java.io.InputStream? = null
        var outputStream: java.io.OutputStream? = null
        return try {
            inputStream = src.openInputStream()
            outputStream = dst.openOutputStream()
            IOUtils.copy(inputStream, outputStream)
            dst
        } catch (e: IOException) {
            null
        } finally {
            IOUtils.closeQuietly(inputStream)
            IOUtils.closeQuietly(outputStream)
        }
    }

    // ── Decode pipeline ──────────────────────────────────────────────

    private fun enqueueRequest(index: Int, priority: Int) {
        if (index < 0) return
        val files = fileList.get()
        if (files != null && index >= files.size) return
        if (!pendingIndices.add(index)) return
        requestQueue.offer(PageRequest(index, priority, seqCounter.incrementAndGet()))
    }

    private fun startDecodeWorkers(scope: CoroutineScope, totalPages: Int) {
        repeat(DECODE_WORKERS) {
            scope.launch {
                while (isActive) {
                    val request = try {
                        // Block this IO thread on queue.take() — cheap
                        // (Dispatchers.IO has up to 64 threads), and
                        // runInterruptible lets coroutine cancellation
                        // unblock the take.
                        runInterruptible { requestQueue.take() }
                    } catch (e: CancellationException) {
                        break
                    } catch (e: InterruptedException) {
                        break
                    }
                    pendingIndices.remove(request.index)
                    processRequest(request.index, totalPages)
                }
            }
        }
    }

    private suspend fun processRequest(index: Int, totalPages: Int) {
        val files = fileList.get() ?: return
        if (index < 0 || index >= files.size) {
            notifyPageFailed(index, GetText.getString(R.string.error_out_of_range))
            return
        }
        try {
            val image = decodePage(files[index])
            if (image != null) {
                notifyPageSucceed(index, image)
                // Successful decode — queue the next few neighbours at
                // PRELOAD priority so the upcoming scroll has decoded
                // pages waiting. Bounded by [PRELOAD_RADIUS].
                for (i in 1..PRELOAD_RADIUS) {
                    val next = index + i
                    if (next >= totalPages) break
                    enqueueRequest(next, PRIO_PRELOAD)
                }
            } else {
                notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            notifyPageFailed(index, GetText.getString(R.string.error_reading_failed))
        } catch (e: Exception) {
            Log.w(TAG, "decode failed for index=$index: ${e.message}")
            notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed))
        }
    }

    /**
     * Decode a single file on [ServiceRegistry.coroutineModule.decoderDispatcher].
     * The dispatcher caps concurrent BitmapFactory work across all
     * providers, so two Dir workers + an LRR session cannot together
     * blow past the global decode-parallelism budget. Decode logic
     * itself lives in [DirImageFiles.decode] and is shared with the
     * detail-page warmup path in [ReaderPageCache].
     */
    private suspend fun decodePage(file: UniFile): Image? {
        val ctx = context ?: return null
        return withContext(ServiceRegistry.coroutineModule.decoderDispatcher) {
            DirImageFiles.decode(ctx, file)
        }
    }

    private data class PageRequest(
        val index: Int,
        val priority: Int,
        val seq: Long,
    ) : Comparable<PageRequest> {
        override fun compareTo(other: PageRequest): Int {
            val byPrio = priority - other.priority
            if (byPrio != 0) return byPrio
            return seq.compareTo(other.seq)
        }
    }

    companion object {
        private val TAG = DirGalleryProvider::class.java.simpleName

        // Priority constants for the request queue. Lower number = served first.
        private const val PRIO_CURRENT = 0
        private const val PRIO_PRELOAD = 1

        /**
         * Concurrent decode worker count. Two is a deliberate
         * conservative pick — it covers "currently visible page +
         * one preload" overlap on mid-tier devices without piling up
         * BitmapFactory allocations. The global cap on simultaneous
         * decodes lives on `decoderDispatcher` (4); this is the
         * per-provider dispatch parallelism.
         */
        private const val DECODE_WORKERS = 2

        /**
         * Pages to enqueue at PRELOAD priority after each successful
         * decode. Aligned with Mihon's `preloadSize = 4` choice but
         * smaller because we run two parallel workers and don't want
         * the queue to outrun the visible window on a fast scroll.
         */
        private const val PRELOAD_RADIUS = 2

        /**
         * Bound on how long the consumer waits for an in-flight warm
         * to land in the slot before falling through to the regular
         * decode pipeline. 300ms is roughly 2× a typical Dir warm
         * (~130ms); enough headroom that a warm that started just
         * before us still wins, but bounded so a slow warm doesn't
         * make the cold path worse.
         */
        private const val WARM_AWAIT_MS: Long = 300L
    }
}
