/*
 * Copyright 2019 Hippo Seven
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
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Log
import com.hippo.a7zip.ArchiveException
import com.hippo.ehviewer.GetText
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.lib.glgallery.GalleryPageView
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import com.hippo.util.NaturalComparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

class ArchiveGalleryProvider(context: Context, uri: Uri) : GalleryProvider2() {

    private val file: UniFile? = UniFile.fromUri(context, uri)

    @Volatile
    private var providerScope: CoroutineScope? = null

    @Volatile
    private var archiveSize: Int = STATE_WAIT

    @Volatile
    private var error: String? = null

    /**
     * Stage 1 queue: page indices waiting to be extracted from the
     * archive. Priority semantics mirror the Dir/LRR providers
     * (lower number serves first; FIFO via [seqCounter] within a
     * tier). 7z extraction is single-threaded by the underlying
     * native library, so a single extractor coroutine drains this.
     */
    private val extractQueue = PriorityBlockingQueue<PageRequest>()
    private val seqCounter = AtomicLong(0L)
    private val pendingExtract: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /**
     * Stage 2 channel: extracted streams handed off to decoder
     * coroutine(s). Unlimited capacity — the extractor is the upstream
     * bottleneck so this never grows beyond the in-flight pipeline.
     */
    private val decodeChannel = Channel<DecodeItem>(Channel.UNLIMITED)

    override fun start() {
        super.start()

        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                ServiceRegistry.coroutineModule.exceptionHandler
        )
        providerScope = scope

        // Stage 1: extractor — opens the archive once, then loops
        // consuming the priority queue.
        scope.launch { extractorLoop(scope) }
        // Stage 2: decoder workers — consume from the channel and
        // hop the actual Image.decode call onto decoderDispatcher.
        repeat(DECODE_WORKERS) {
            scope.launch { decoderLoop(scope) }
        }
    }

    override fun stop() {
        super.stop()
        decodeChannel.close()
        providerScope?.cancel()
        providerScope = null
        extractQueue.clear()
        pendingExtract.clear()
    }

    override fun size(): Int = archiveSize

    override fun onRequest(index: Int) {
        enqueueExtract(index, PRIO_CURRENT)
        notifyPageWait(index)
    }

    override fun onForceRequest(index: Int) {
        enqueueExtract(index, PRIO_CURRENT)
    }

    override fun onCancelRequest(index: Int) {
        if (extractQueue.removeIf { it.index == index }) {
            pendingExtract.remove(index)
        }
    }

    override fun getError(): String? = error

    override fun getImageFilename(index: Int): String {
        // LEGACY: archive entries lack original filenames; return index as fallback
        return index.toString()
    }

    override fun save(index: Int, file: UniFile): Boolean {
        // LEGACY: save not implemented for archive stream-based entries
        return false
    }

    override fun save(index: Int, dir: UniFile, filename: String): UniFile? {
        // LEGACY: save-to-dir not implemented for archive stream-based entries
        return null
    }

    private fun enqueueExtract(index: Int, priority: Int) {
        if (index < 0) return
        val sz = archiveSize
        if (sz > 0 && index >= sz) return
        if (!pendingExtract.add(index)) return
        extractQueue.offer(PageRequest(index, priority, seqCounter.incrementAndGet()))
    }

    private suspend fun extractorLoop(scope: CoroutineScope) {
        var uraf: com.hippo.unifile.UniRandomAccessFile? = null
        if (file != null) {
            try {
                uraf = file.createRandomAccessFile("r")
            } catch (e: IOException) {
                Log.w(TAG, "createRandomAccessFile failed: ${e.message}")
            }
        }
        if (uraf == null) {
            archiveSize = STATE_ERROR
            error = GetText.getString(R.string.error_reading_failed)
            notifyDataChanged()
            return
        }

        var archive: A7ZipArchive? = null
        try {
            archive = A7ZipArchive.create(uraf)
        } catch (e: ArchiveException) {
            Log.w(TAG, "A7ZipArchive.create failed: ${e.message}")
        }
        if (archive == null) {
            archiveSize = STATE_ERROR
            error = GetText.getString(R.string.error_invalid_archive)
            notifyDataChanged()
            return
        }

        val entries = archive.archiveEntries
        Collections.sort(entries, naturalComparator)

        archiveSize = entries.size
        notifyDataChanged()

        while (scope.isActive) {
            val request = try {
                runInterruptible { extractQueue.take() }
            } catch (e: CancellationException) {
                break
            } catch (e: InterruptedException) {
                break
            }
            pendingExtract.remove(request.index)

            val index = request.index
            if (index < 0 || index >= entries.size) {
                notifyPageFailed(index, GetText.getString(R.string.error_out_of_range))
                continue
            }

            val pipe = Pipe(PIPE_BUFFER_BYTES)
            // Hand the *input* end of the pipe off to the decoder
            // before starting the extract — extract writes to the
            // pipe's output side and would block once the pipe fills
            // if the decoder hasn't begun draining.
            decodeChannel.trySend(DecodeItem(index, pipe.inputStream))

            try {
                runInterruptible {
                    entries[index].extract(pipe.outputStream)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ArchiveException) {
                Log.w(TAG, "extract failed for index=$index: ${e.message}")
                // Decoder will see EOF on the stream and notify failure.
            } catch (e: Exception) {
                Log.w(TAG, "extract unexpected error for index=$index: ${e.message}")
            }
        }
    }

    private suspend fun decoderLoop(scope: CoroutineScope) {
        try {
            while (scope.isActive) {
                val item = try {
                    decodeChannel.receive()
                } catch (e: ClosedReceiveChannelException) {
                    break
                }
                val image = withContext(ServiceRegistry.coroutineModule.decoderDispatcher) {
                    try {
                        Image.decode(BitmapDrawable.createFromStream(item.stream, null), false)
                    } catch (e: Exception) {
                        Log.w(TAG, "decode failed for index=${item.index}: ${e.message}")
                        null
                    }
                }
                if (image != null) {
                    notifyPageSucceed(item.index, image)
                } else {
                    notifyPageFailed(item.index, GetText.getString(R.string.error_decoding_failed))
                }
            }
        } catch (e: CancellationException) {
            throw e
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

    private data class DecodeItem(val index: Int, val stream: InputStream)

    companion object {
        private val TAG = ArchiveGalleryProvider::class.java.simpleName

        private const val PRIO_CURRENT = 0

        /**
         * Concurrent decoder workers. Two — same rationale as
         * DirGalleryProvider: enough to overlap the in-flight
         * extracted streams without piling up large bitmap
         * allocations. decoderDispatcher's global cap (4) governs
         * across all providers.
         */
        private const val DECODE_WORKERS = 2

        private const val PIPE_BUFFER_BYTES = 4 * 1024

        private val naturalComparator =
            Comparator<A7ZipArchive.A7ZipArchiveEntry> { o1, o2 ->
                NaturalComparator().compare(o1.path, o2.path)
            }
    }
}
