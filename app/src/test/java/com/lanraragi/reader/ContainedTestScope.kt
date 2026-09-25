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
 * hygiene: test fakes that throw NotImplementedError("not needed") from
 * unimplemented getters DO get reached by those coroutines, and
 * NotImplementedError is an Error — the production `catch (e: Exception)`
 * can't contain it. Without a handler the Error escapes to the global
 * handler, where kotlinx-coroutines-test's collector picks it up and fails
 * whichever unrelated runTest runs NEXT with UncaughtExceptionsBeforeTest
 * (the victim drifts with suite ordering). Mirrors the production
 * CoroutineModule scopes, which all carry a handler.
 */
fun containedTestScope(): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Unconfined +
        CoroutineExceptionHandler { _, t ->
            println("testScope contained: $t")
        }
)
