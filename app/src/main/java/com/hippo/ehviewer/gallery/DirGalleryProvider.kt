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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
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

        // SupervisorJob so a single failed worker doesn't tear down the
        // sibling worker(s). Anchored on Dispatchers.IO; the actual decode
        // work hops onto decoderDispatcher inside processRequest below.
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                ServiceRegistry.coroutineModule.exceptionHandler
        )
        providerScope = scope

        // Async: load server progress + local intra-page scroll fraction
        // and jump if newer / non-zero.
        if (arcId != null && serverUrl != null && context != null) {
            scope.launch {
                try {
                    val client = ServiceRegistry.networkModule.okHttpClient
                    val metadata = runSuspend {
                        LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcId)
                    }
                    val savedFraction = try {
                        ServiceRegistry.dataModule.historyRepository
                            .getHistoryScrollFraction(arcId!!) ?: 0f
                    } catch (e: Exception) {
                        Log.w(TAG, "[PROGRESS] Failed to load scroll fraction: ${e.message}")
                        0f
                    }.coerceIn(0f, 1f)
                    Log.i(TAG, "[PROGRESS] Server metadata: progress=${metadata.progress}" +
                            " lastreadtime=${metadata.lastreadtime}" +
                            " savedFraction=$savedFraction")
                    if (metadata.progress > 0 || savedFraction > 0f) {
                        val serverPage0 = (metadata.progress - 1).coerceAtLeast(0)
                        val serverTs = metadata.lastreadtime
                        val localTs = if (arcId != null) loadReadingTimestamp(context, arcId!!) else 0L
                        Log.i(TAG, "[PROGRESS] serverPage0=$serverPage0" +
                                " serverTs=$serverTs localTs=$localTs" +
                                " localPage=$startPageValue")
                        val resolvedPage: Int
                        if (metadata.progress <= 0) {
                            // Server has no progress, but we still might have a
                            // local intra-page fraction to restore on page 0.
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
                                    val gvNow = galleryView ?: return@postDelayed
                                    if (savedFraction > 0f) {
                                        gvNow.setCurrentPageScrollFraction(resolvedPage, savedFraction)
                                        Log.i(TAG, "[PROGRESS] setCurrentPageScrollFraction(" +
                                                "$resolvedPage, $savedFraction) called")
                                    } else {
                                        gvNow.setCurrentPage(resolvedPage)
                                        Log.i(TAG, "[PROGRESS] setCurrentPage($resolvedPage) called")
                                    }
                                }, 300)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[PROGRESS] Failed to load server progress: ${e.message}")
                }
            }
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
    }
}
