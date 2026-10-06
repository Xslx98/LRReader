package com.lanraragi.reader.gallery

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether a reader is on screen (audit 2026-10-04 PERF-13). Background downloads
 * shrink their page window meanwhile, so the page the user waits for is not
 * queued behind bulk download traffic on a small (4-worker) server.
 * A counter, not a flag: a stop of one reader instance must not clear another's.
 */
object ForegroundReading {

    private val readers = AtomicInteger(0)

    val isActive: Boolean get() = readers.get() > 0

    fun enter() {
        readers.incrementAndGet()
    }

    fun exit() {
        readers.updateAndGet { maxOf(0, it - 1) }
    }

    internal fun resetForTest() = readers.set(0)
}
