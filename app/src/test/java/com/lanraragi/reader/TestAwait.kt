package com.lanraragi.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.job
import org.robolectric.shadows.ShadowLooper

/**
 * Event-driven waits for Robolectric tests, replacing fixed Thread.sleep
 * "drains": they return as soon as the condition holds, and fail loudly at
 * the deadline instead of asserting on whatever state a slow runner reached.
 */
fun awaitUntil(
    timeoutMs: Long = 10_000,
    message: String = "condition not met within ${timeoutMs}ms",
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        ShadowLooper.idleMainLooper()
        if (condition()) return
        if (System.currentTimeMillis() > deadline) throw AssertionError(message)
        Thread.sleep(POLL_MS)
    }
}

/**
 * Wait until every coroutine launched on [vm]'s viewModelScope has finished,
 * then idle the main looper so their Main-thread emissions are published.
 * Only for view models without long-lived collectors on viewModelScope.
 * This also makes negative assertions ("no event was emitted") meaningful:
 * the work has actually completed rather than merely had time to.
 */
fun awaitViewModelIdle(vm: ViewModel, timeoutMs: Long = 10_000) {
    val job = vm.viewModelScope.coroutineContext.job
    awaitUntil(timeoutMs, "viewModelScope still busy after ${timeoutMs}ms") {
        job.children.none { !it.isCompleted }
    }
    ShadowLooper.idleMainLooper()
}

private const val POLL_MS = 10L
