package com.hippo.ehviewer.download

import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds how long a single download run may stay paused waiting for the network
 * to return. Shared across a worker's concurrent page coroutines, so the budget
 * measures *wall-clock outage time* (from the first wait), not summed per-page
 * waits. Pure logic (clock injected) so the budget math is unit-testable.
 *
 * @param totalBudgetMillis total allowed wait; [Long.MAX_VALUE] means "never time out".
 * @param clock monotonic time source; defaults to [SystemClock.elapsedRealtime].
 */
class NetworkWaitBudget(
    val totalBudgetMillis: Long,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val firstWaitAt = AtomicLong(-1L)

    /** Record the instant the worker first started waiting (idempotent). */
    fun markWaitStartIfNeeded(now: Long) {
        firstWaitAt.compareAndSet(-1L, now)
    }

    /** Remaining wait budget in millis; [Long.MAX_VALUE] when unbounded. */
    fun remainingMillis(now: Long): Long {
        if (totalBudgetMillis == Long.MAX_VALUE) return Long.MAX_VALUE
        val start = firstWaitAt.get()
        if (start < 0L) return totalBudgetMillis
        return (totalBudgetMillis - (now - start)).coerceAtLeast(0L)
    }

    /**
     * Suspend until [availability] reports `true`, or until the budget is
     * exhausted. Returns `true` if the network is (or became) available within
     * budget, `false` if the budget ran out first.
     */
    suspend fun awaitNetworkOrExpire(availability: StateFlow<Boolean>): Boolean {
        if (availability.value) return true
        markWaitStartIfNeeded(clock())
        val remaining = remainingMillis(clock())
        if (remaining <= 0L) return false
        if (remaining == Long.MAX_VALUE) {
            availability.first { it }
            return true
        }
        return withTimeoutOrNull(remaining) { availability.first { it } } != null
    }

    companion object {
        /** Build from a minutes setting; `minutes <= 0` means "never time out". */
        fun fromMinutes(
            minutes: Int,
            clock: () -> Long = { SystemClock.elapsedRealtime() },
        ): NetworkWaitBudget {
            val millis = if (minutes <= 0) Long.MAX_VALUE else minutes * 60_000L
            return NetworkWaitBudget(millis, clock)
        }
    }
}
