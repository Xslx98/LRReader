package com.lanraragi.reader.module

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.Analytics
import com.lanraragi.reader.Crash
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException

/**
 * Provides properly configured [CoroutineScope] instances for the application.
 *
 * **Why this module exists:**
 * Production code has no `runBlocking` bridge; background work is launched
 * with `launch {}`, and every launch site needs a [CoroutineExceptionHandler]
 * to prevent silent crashes.
 *
 * **Design rationale (per official Kotlin docs):**
 * - [CoroutineExceptionHandler] is invoked only on **uncaught** exceptions in
 *   **root** coroutines. Child coroutines propagate to their parent.
 *   (https://kotlinlang.org/docs/exception-handling.html)
 * - [SupervisorJob] ensures one child's failure does not cancel siblings.
 *   Direct children of a `supervisorScope` treat the installed CEH the same
 *   as root coroutines do.
 * - The handler is for **logging/cleanup only** — "you cannot recover from the
 *   exception in the CoroutineExceptionHandler" (official docs).
 *
 * @param nonFatalSink receives every exception that reaches [exceptionHandler];
 *   production writes a non-fatal report (audit 2026-10-04 C06 / STAB-02).
 * @param debugRethrow gets every handled exception that is not an [IOException]
 *   (a programming error rather than a network or disk failure). Debug builds
 *   rethrow it on the main looper so the bug crashes during development
 *   instead of a feature silently doing nothing (STAB-02).
 */
class CoroutineModule(
    private val nonFatalSink: (Throwable) -> Unit = Crash::saveNonFatal,
    private val debugRethrow: (Throwable) -> Unit = {},
) : ICoroutineModule {

    private val tag = "CoroutineModule"

    /**
     * Global exception handler that logs uncaught coroutine exceptions, writes
     * a non-fatal report and, for programming errors, calls [debugRethrow].
     * Installed on all scopes created by this module.
     */
    override val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Uncaught coroutine exception", throwable)
        Analytics.recordException(throwable)
        try {
            nonFatalSink(throwable)
        } catch (e: Throwable) {
            Log.e(tag, "Record non-fatal report", e)
        }
        if (throwable !is IOException) debugRethrow(throwable)
    }

    /**
     * Application-scoped [CoroutineScope] backed by [SupervisorJob] + [Dispatchers.Main].
     *
     * Use for work tied to the application lifecycle (not to a specific Activity/Fragment).
     * For Fragment/Activity-scoped work, use `viewLifecycleOwner.lifecycleScope` with
     * [exceptionHandler] added to its context:
     * ```
     * viewLifecycleOwner.lifecycleScope.launch(coroutineModule.exceptionHandler) { ... }
     * ```
     */
    override val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler
    )

    /**
     * IO-scoped [CoroutineScope] for background work (network, database, file I/O).
     * Backed by [SupervisorJob] so individual task failures don't cancel the scope.
     */
    override val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + exceptionHandler
    )

    /**
     * Decoder dispatcher: a view of [Dispatchers.IO] capped at
     * [DECODER_PARALLELISM] concurrent tasks via
     * [kotlinx.coroutines.CoroutineDispatcher.limitedParallelism].
     *
     * Why a separate dispatcher rather than just `Dispatchers.IO`:
     * `Dispatchers.IO` defaults to a 64-thread pool, which lets
     * unrelated I/O proceed while a few decodes are in flight. A
     * `limitedParallelism` view shares the same backing pool but
     * caps the *decode* tasks specifically, so a burst of pages
     * doesn't drown out other I/O (network, DB) or push memory
     * into trim. Empirically the value mirrors Coil's
     * `bitmapFactoryMaxParallelism = 4`.
     */
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val decoderDispatcher = Dispatchers.IO.limitedParallelism(DECODER_PARALLELISM)

    override fun destroy() {
        applicationScope.cancel()
        ioScope.cancel()
    }

    companion object {
        /** Production [debugRethrow] (wired in [com.lanraragi.reader.ServiceRegistry.initialize]). */
        fun rethrowOnMainInDebug(throwable: Throwable) {
            // Robolectric boots the real Application: a posted throw would fail an
            // unrelated test when it next idles the main looper.
            if (BuildConfig.DEBUG && Build.FINGERPRINT != "robolectric") {
                Handler(Looper.getMainLooper()).post { throw throwable }
            }
        }

        /**
         * Max concurrent decode tasks running through
         * [decoderDispatcher]. Aligns with Coil's
         * `bitmapFactoryMaxParallelism` default of 4 — high enough
         * to overlap "current page + a couple of preloads" but low
         * enough that large-bitmap allocations don't pile up.
         */
        private const val DECODER_PARALLELISM = 4
    }
}
