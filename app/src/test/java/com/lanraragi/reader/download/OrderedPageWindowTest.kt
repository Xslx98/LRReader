package com.lanraragi.reader.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class OrderedPageWindowTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun everyIndexProcessedExactlyOnce() = runBlocking {
        // Repeated to shake out the lost-update race on the window's
        // lowest-unfinished flow (a regression would hang, hence the timeout).
        withTimeout(30_000) {
            repeat(20) {
                val claimed = Collections.synchronizedList(mutableListOf<Int>())
                OrderedPageWindow.run(scope, total = 50, workers = 8, isCancelled = { false }) { i ->
                    claimed += i
                    delay((i % 3).toLong())
                }
                assertEquals((0 until 50).toList(), claimed.sorted())
                assertEquals(50, claimed.size)
            }
        }
    }

    @Test
    fun singleWorkerProcessesStrictlyInOrder() = runBlocking {
        val claimed = mutableListOf<Int>()
        withTimeout(10_000) {
            OrderedPageWindow.run(scope, total = 20, workers = 1, isCancelled = { false }) { i ->
                claimed += i
            }
        }
        assertEquals((0 until 20).toList(), claimed)
    }

    @Test
    fun failedPageDoesNotStallTheWindow() = runBlocking {
        val processed = Collections.synchronizedSet(mutableSetOf<Int>())
        withTimeout(5_000) {
            OrderedPageWindow.run(scope, total = 40, workers = 4, isCancelled = { false }) { i ->
                processed += i
                // Simulate the worker's per-page retry loop giving up: it
                // reports failure and returns normally without producing the
                // page. That must still release the window slot.
                if (i == 2) return@run
                delay(1)
            }
        }
        assertEquals(40, processed.size)
    }

    @Test
    fun slowPageHoldsTheWindow_noPageMoreThanWorkersAhead() = runBlocking {
        val workers = 8
        val gate = CompletableDeferred<Unit>()
        val started = Collections.synchronizedSet(mutableSetOf<Int>())
        val maxStartedWhileBlocked = AtomicInteger(-1)
        val run = scope.launch {
            OrderedPageWindow.run(scope, total = 100, workers = workers, isCancelled = { false }) { i ->
                started += i
                if (i == 0) gate.await()
            }
        }
        // Let the free workers drain everything they can while page 0 blocks.
        delay(300)
        maxStartedWhileBlocked.set(started.max())
        gate.complete(Unit)
        withTimeout(5_000) { run.join() }

        // With page 0 stuck, the other 7 workers may only advance to index 7.
        assertEquals(workers - 1, maxStartedWhileBlocked.get())
        assertEquals(100, started.size)
    }

    @Test
    fun cancelledWindowStopsIssuingNewPages() = runBlocking {
        val processed = AtomicInteger(0)
        var cancelled = false
        OrderedPageWindow.run(scope, total = 100, workers = 2, isCancelled = { cancelled }) { i ->
            processed.incrementAndGet()
            if (i == 3) cancelled = true
        }
        assertTrue("cancel should cut the run short", processed.get() < 100)
    }

    @Test
    fun zeroPagesCompletesImmediately() = runBlocking {
        OrderedPageWindow.run(scope, total = 0, workers = 8, isCancelled = { false }) {
            error("must not be called")
        }
    }
}
