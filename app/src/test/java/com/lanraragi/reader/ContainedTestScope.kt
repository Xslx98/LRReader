package com.lanraragi.reader

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Test scope for code under test that launches fire-and-forget coroutines
 * (e.g. constructing a real DownloadManager, whose init fires
 * syncRatingsFromServer on the injected scope).
 *
 * The CoroutineExceptionHandler is load-bearing for suite stability, not
 * hygiene: test fakes that throw from unimplemented getters DO get reached by
 * those coroutines. Anything that escapes a coroutine without a handler goes
 * to the global handler, where kotlinx-coroutines-test's collector picks it
 * up and fails whichever unrelated runTest runs NEXT in the same Robolectric
 * sandbox with UncaughtExceptionsBeforeTest (the victim drifts with suite
 * ordering). The fakes throw UnsupportedOperationException, which production
 * `catch (e: Exception)` blocks contain; they used to throw
 * NotImplementedError, an Error that nothing caught (2026-11: 1042 such
 * escapes per run from DownloadManagerTest's raw IO scopes). Mirrors the
 * production CoroutineModule scopes, which all carry a handler.
 */
fun containedTestScope(): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Unconfined +
        CoroutineExceptionHandler { _, t ->
            println("testScope contained: $t")
        }
)
