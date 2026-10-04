package com.lanraragi.reader.module

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-04 C06 / STAB-02: an exception that reaches the shared
 * coroutine handler is written as a non-fatal report instead of only logged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class CoroutineModuleNonFatalTest {

    @Test
    fun uncaughtException_reachesTheNonFatalSink() = runBlocking {
        val seen = CompletableDeferred<Throwable>()
        val module = CoroutineModule(nonFatalSink = { seen.complete(it) })
        val boom = IllegalStateException("boom")
        module.ioScope.launch { throw boom }
        assertSame(boom, withTimeout(5_000) { seen.await() })
        module.destroy()
    }

    @Test
    fun throwingSink_doesNotBreakTheHandlerOrTheScope() = runBlocking {
        val module = CoroutineModule(nonFatalSink = { error("sink failed") })
        module.ioScope.launch { throw IllegalStateException("first") }.join()
        val second = CompletableDeferred<Unit>()
        module.ioScope.launch { second.complete(Unit) }
        withTimeout(5_000) { second.await() }
        module.destroy()
    }
}
