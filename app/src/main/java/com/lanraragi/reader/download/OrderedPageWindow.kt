package com.lanraragi.reader.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ordered sliding-window page scheduler for [LRRDownloadWorker].
 *
 * [workers] coroutines claim page indices from one shared ascending cursor,
 * and a claimed index `i` may only start once `i < lowestUnfinished + workers`.
 * So at most [workers] pages are in flight, they are always the [workers]
 * lowest unfinished indices, and a page that is slow or retrying holds the
 * window instead of being overtaken by the rest of the archive.
 *
 * This replaces the earlier "launch every page, gate with a semaphore" shape,
 * which admitted pages in launch order but let a slow page fall arbitrarily
 * far behind. The ordering matters to the reader: a session that consumes
 * the download directory while the download is still running
 * (`LRRGalleryProvider` hybrid mode) sees pages appear in reading order.
 */
internal object OrderedPageWindow {

    /**
     * Process indices `0 until total` with [process], keeping at most
     * [workers] in flight and never more than [workers] ahead of the lowest
     * unfinished index. [process] returning (normally or by a caught
     * failure) counts the page as finished for window purposes — a failed
     * page must not stall the window. [isCancelled] is polled before each
     * claim; a cancelled window stops issuing new pages but does not
     * interrupt pages already in flight (callers cancel their scope for that).
     */
    suspend fun run(
        scope: CoroutineScope,
        total: Int,
        workers: Int,
        isCancelled: () -> Boolean,
        process: suspend (index: Int) -> Unit,
    ) {
        require(workers > 0) { "workers must be positive" }
        if (total <= 0) return
        val cursor = AtomicInteger(0)
        val tracker = WindowTracker(total)
        List(minOf(workers, total)) {
            scope.async {
                while (!isCancelled()) {
                    val index = cursor.getAndIncrement()
                    if (index >= total) break
                    // Wait until this index falls inside the window.
                    tracker.lowestUnfinished.first { lowest -> index - lowest < workers }
                    try {
                        process(index)
                    } finally {
                        tracker.markDone(index)
                    }
                }
            }
        }.awaitAll()
    }

    private class WindowTracker(total: Int) {
        private val done = BooleanArray(total)
        private var lowest = 0
        val lowestUnfinished = MutableStateFlow(0)

        fun markDone(index: Int) {
            val advanced = synchronized(done) {
                done[index] = true
                while (lowest < done.size && done[lowest]) lowest++
                lowest
            }
            lowestUnfinished.value = advanced
        }
    }
}
