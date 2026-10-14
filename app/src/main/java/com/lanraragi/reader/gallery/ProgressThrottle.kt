package com.lanraragi.reader.gallery

import android.os.SystemClock

/**
 * Coalesces a page download's per-chunk progress into what the reader can
 * show: a new whole percent at most every [minIntervalMs], plus the final
 * 100 % (audit 2026-10-13 PERF-07). Each emission becomes a GL-thread task,
 * so a 6 MB page must not post one per 64 KB read.
 */
internal class ProgressThrottle(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private var lastPercent = -1
    private var lastAt = 0L

    /** Whether progress [fraction] (0..1) should be reported now. */
    fun shouldEmit(fraction: Float): Boolean {
        val percent = (fraction * PERCENT_MAX).toInt().coerceIn(0, PERCENT_MAX)
        if (percent == lastPercent) return false
        val now = clock()
        if (lastPercent >= 0 && percent < PERCENT_MAX && now - lastAt < minIntervalMs) return false
        lastPercent = percent
        lastAt = now
        return true
    }

    private companion object {
        const val MIN_INTERVAL_MS = 100L
        const val PERCENT_MAX = 100
    }
}
