package com.lanraragi.reader.gallery

import android.os.Handler
import android.os.Looper

/**
 * Writes the reader's local position at most once per [delayMs]: a page turn
 * records the page, and the latest one is written when the delay runs out or
 * on [flush] (the provider's stop). Each write re-serialises the whole
 * `reading_progress` file, so writing on every turn of a fast flip or a
 * scroll-mode read cost one full file rewrite per page (audit 2026-10-13
 * PERF-10). Losing up to [delayMs] of position to process death is covered by
 * the server sync, which still sees every page.
 */
internal class DeferredProgressSave(
    private val delayMs: Long = DELAY_MS,
    handler: Handler? = null,
    private val write: (page: Int) -> Unit,
) {
    // Lazy: providers are also built in plain JVM tests, where there is no main looper.
    private val handler by lazy { handler ?: Handler(Looper.getMainLooper()) }
    private val lock = Any()
    private var pending: Int? = null // guarded by lock
    private var scheduled = false // guarded by lock
    private val flushTask = Runnable { flush() }

    /** Records [page]; it is written within [delayMs]. Any thread. */
    fun submit(page: Int) {
        val schedule = synchronized(lock) {
            pending = page
            (!scheduled).also { scheduled = true }
        }
        if (schedule) handler.postDelayed(flushTask, delayMs)
    }

    /** Writes the recorded page now, if any. */
    fun flush() {
        // Unschedule first: a submit racing in after the lock below then posts
        // a fresh task instead of having it removed here.
        handler.removeCallbacks(flushTask)
        val page = synchronized(lock) {
            scheduled = false
            pending.also { pending = null }
        }
        if (page != null) write(page)
    }

    private companion object {
        const val DELAY_MS = 1_000L
    }
}
