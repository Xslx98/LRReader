package com.hippo.ehviewer.module

import android.util.Log
import com.hippo.ehviewer.Analytics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Provides properly configured [CoroutineScope] instances for the application.
 *
 * **Why this module exists:**
 * The codebase currently bridges suspend functions to Java via `runBlocking`.
 * As Java→Kotlin migration progresses and UI code starts using `launch {}`,
 * every launch site needs a [CoroutineExceptionHandler] to prevent silent crashes.
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
 */
class CoroutineModule : ICoroutineModule {

    private val tag = "CoroutineModule"

    private val _uncaughtErrors = MutableSharedFlow<Throwable>(
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Observable stream of uncaught coroutine exceptions.
     * UI layers can optionally subscribe to display error notifications.
     */
    override val uncaughtErrors: SharedFlow<Throwable> = _uncaughtErrors.asSharedFlow()

    /**
     * Global exception handler that logs uncaught coroutine exceptions,
     * reports them to Analytics, and emits them on [uncaughtErrors].
     * Installed on all scopes created by this module.
     */
    override val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Uncaught coroutine exception", throwable)
        Analytics.recordException(throwable)
        _uncaughtErrors.tryEmit(throwable)
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

    private companion object {
        /**
         * Max concurrent decode tasks running through
         * [decoderDispatcher]. Aligns with Coil's
         * `bitmapFactoryMaxParallelism` default of 4 — high enough
         * to overlap "current page + a couple of preloads" but low
         * enough that large-bitmap allocations don't pile up.
         */
        const val DECODER_PARALLELISM = 4
    }
}
