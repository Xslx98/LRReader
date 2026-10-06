package com.lanraragi.reader.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit 2026-10-04 C08: the download service's command loop survives a
 * throwing command, and reports the startId of the command it just handled
 * (the service's `stopSelfResult` argument), never a newer queued one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadCommandQueueTest {

    @Test
    fun throwingCommand_isReportedAndLaterCommandsStillRun() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val handled = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val afterEach = mutableListOf<Int>()
        val queue = DownloadCommandQueue<String>(
            scope = backgroundScope,
            handlerDispatcher = dispatcher,
            awaitReady = {},
            handle = { if (it == "boom") throw IllegalStateException(it) else handled += it },
            onError = { cmd, e -> errors += "$cmd:${e.javaClass.simpleName}" },
            afterEach = { afterEach += it },
        )

        queue.submit("a", 1)
        queue.submit("boom", 2)
        queue.submit("c", 3)
        testScheduler.runCurrent()

        assertEquals(listOf("a", "c"), handled)
        assertEquals(listOf("boom:IllegalStateException"), errors)
        // The failed command still gets its stop check.
        assertEquals(listOf(1, 2, 3), afterEach)
    }

    /** Audit 2026-10-06 L2: a throwing stop check or error report must not end the only consumer. */
    @Test
    fun throwingAfterEachOrOnError_doesNotStopTheConsumer() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val handled = mutableListOf<String>()
        val afterEach = mutableListOf<Int>()
        val queue = DownloadCommandQueue<String>(
            scope = backgroundScope,
            handlerDispatcher = dispatcher,
            awaitReady = {},
            handle = { if (it == "boom") throw IllegalStateException(it) else handled += it },
            onError = { _, _ -> throw IllegalStateException("report failed") },
            afterEach = {
                afterEach += it
                if (it == 1) throw IllegalStateException("stop check failed")
            },
        )

        queue.submit("a", 1)
        queue.submit("boom", 2)
        queue.submit("c", 3)
        testScheduler.runCurrent()

        assertEquals(listOf("a", "c"), handled)
        assertEquals(listOf(1, 2, 3), afterEach)
    }

    @Test
    fun afterEach_reportsHandledStartIdWhileNewerCommandsWait() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val ready = CompletableDeferred<Unit>()
        val seenWhenStopChecked = mutableListOf<Pair<Int, List<String>>>()
        val handled = mutableListOf<String>()
        val queue = DownloadCommandQueue<String>(
            scope = backgroundScope,
            handlerDispatcher = dispatcher,
            awaitReady = { ready.await() },
            handle = { handled += it },
            onError = { _, _ -> },
            afterEach = { seenWhenStopChecked += it to handled.toList() },
        )

        // Both commands are delivered before the first one runs (init pending).
        queue.submit("delete", 1)
        queue.submit("start", 2)
        ready.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(
            listOf(1 to listOf("delete"), 2 to listOf("delete", "start")),
            seenWhenStopChecked
        )
    }
}
