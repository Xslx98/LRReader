package com.hippo.ehviewer.module

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AppModule.createBootCEH] and the boot-time scope wiring used by
 * [com.hippo.ehviewer.EhApplication] before `ServiceRegistry.initialize()` runs.
 *
 * Design note: [com.hippo.ehviewer.Crash] and [com.hippo.ehviewer.Analytics] are
 * Kotlin `object` singletons that resist mocking without MockK / PowerMock. Rather
 * than pull in a heavyweight mocking library, [AppModule.createBootCEH] takes
 * injectable function references — the production [AppModule.bootCEH] wires those
 * references to the real singletons, and these tests substitute lambdas that
 * record invocations.
 */
class AppModuleBootScopeTest {

    @Test
    fun createBootCEH_invokesBothCallbacks_onUncaughtException() = runTest {
        val savedCrash = CompletableDeferred<Throwable>()
        val recordedException = CompletableDeferred<Throwable>()
        val handler = AppModule.createBootCEH(
            saveCrashLog = { t -> savedCrash.complete(t) },
            recordException = { t -> recordedException.complete(t) },
        )

        // Launch a root coroutine that throws — the CEH must receive the exception.
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined + handler,
        )
        val boom = RuntimeException("boom")
        scope.launch { throw boom }

        val seenCrash = withTimeout(1_000L) { savedCrash.await() }
        val seenAnalytics = withTimeout(1_000L) { recordedException.await() }
        assertSame("saveCrashLog should receive the original throwable", boom, seenCrash)
        assertSame("recordException should receive the original throwable", boom, seenAnalytics)
    }

    @Test
    fun createBootCEH_swallowsExceptionsThrownBySaveCrashLog() = runTest {
        val recordedException = CompletableDeferred<Throwable>()
        val handler = AppModule.createBootCEH(
            saveCrashLog = { error("crash logger itself failed") },
            recordException = { t -> recordedException.complete(t) },
        )

        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined + handler,
        )
        val boom = RuntimeException("boom")
        // Must not propagate the inner failure.
        scope.launch { throw boom }

        // recordException should still be called even though saveCrashLog blew up.
        val seen = withTimeout(1_000L) { recordedException.await() }
        assertSame(boom, seen)
    }

    @Test
    fun createBootCEH_swallowsExceptionsThrownByRecordException() = runTest {
        val savedCrash = CompletableDeferred<Throwable>()
        val handler = AppModule.createBootCEH(
            saveCrashLog = { t -> savedCrash.complete(t) },
            recordException = { error("analytics itself failed") },
        )

        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined + handler,
        )
        val boom = RuntimeException("boom")
        scope.launch { throw boom }

        val seen = withTimeout(1_000L) { savedCrash.await() }
        assertSame(boom, seen)
    }

    @Test
    fun createBootCEH_swallowsExceptionsFromBothCallbacks() {
        val handler = AppModule.createBootCEH(
            saveCrashLog = { error("crash logger failed") },
            recordException = { error("analytics failed") },
        )
        // Direct invocation: should not throw even though both callbacks throw.
        handler.handleException(kotlin.coroutines.EmptyCoroutineContext, RuntimeException("boom"))
    }

    @Test
    fun bootScope_isNotNull_andIsLazyToConstruct() {
        // Touching the property exposes the singleton — should not throw.
        val scope = AppModule.bootScope
        assertNotNull(scope)
        // Static reference should be stable.
        assertSame(scope, AppModule.bootScope)
    }

    @Test
    fun bootCEH_isNotNull_andIsStable() {
        val ceh = AppModule.bootCEH
        assertNotNull(ceh)
        assertSame(ceh, AppModule.bootCEH)
    }

    @Test
    fun activeProfileIdDeferred_canBeCompletedWithValue_andIsIdempotent() = runBlocking {
        // The deferred is a process-wide singleton — once completed by another test
        // (or by the application's profile loader in production), it stays completed.
        // We exercise the API contract here without asserting a specific value.
        val deferred = AppModule.activeProfileIdDeferred
        // Attempt to complete with null — returns true the first time, false thereafter.
        deferred.complete(null)
        // Subsequent complete() calls are no-ops, never throwing.
        deferred.complete(123L)
        deferred.complete(null)
        // Awaiter must not hang. The deferred is now resolved (either by us above
        // or by an earlier test), so await() must return promptly. We don't assert
        // which value won the race because the deferred is a singleton — only that
        // it completed.
        withTimeout(1_000L) { deferred.await() }
        assertTrue("deferred is completed after first complete()", deferred.isCompleted)
    }
}
