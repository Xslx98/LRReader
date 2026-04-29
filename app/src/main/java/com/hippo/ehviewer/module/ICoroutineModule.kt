package com.hippo.ehviewer.module

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstraction over [CoroutineModule] to allow ServiceRegistry consumers to depend on the
 * contract rather than the concrete implementation. Enables test-time substitution with
 * deterministic dispatchers.
 *
 * See [CoroutineModule] for design rationale around [CoroutineExceptionHandler] + [SupervisorJob].
 */
interface ICoroutineModule {

    /**
     * Exception handler installed on all scopes managed by this module. Logs, reports to
     * Analytics, and emits on [uncaughtErrors]. May be added to `lifecycleScope.launch()`
     * sites that want the same handling.
     */
    val exceptionHandler: CoroutineExceptionHandler

    /**
     * Application-scoped coroutines (Main dispatcher, [kotlinx.coroutines.SupervisorJob]).
     * Used for lifecycle-agnostic work.
     */
    val applicationScope: CoroutineScope

    /**
     * Background-work scope (IO dispatcher, [kotlinx.coroutines.SupervisorJob]).
     * Used for network, database, and file I/O.
     */
    val ioScope: CoroutineScope

    /**
     * Bounded-parallelism dispatcher dedicated to image decoding (the
     * `Image.decode` JNI call and its callers in the gallery providers).
     *
     * Backed by [kotlinx.coroutines.Dispatchers.IO] but capped via
     * [kotlinx.coroutines.CoroutineDispatcher.limitedParallelism] at a
     * small concurrency level. This is the project-side analogue of
     * Coil's `bitmapFactoryMaxParallelism = 4`: enough parallelism to
     * keep the decoder pipeline busy when the user opens or fast-
     * scrolls a gallery, but bounded so simultaneous large-bitmap
     * allocations don't push the app into trim levels.
     *
     * Use via `withContext(decoderDispatcher) { Image.decode(...) }`
     * at every decode call site so the cap applies globally across
     * all providers (Dir / LRR / Archive) and the detail-page warmup.
     */
    val decoderDispatcher: CoroutineDispatcher

    /**
     * Observable stream of uncaught coroutine exceptions. UI layers can subscribe to
     * surface error notifications.
     */
    val uncaughtErrors: SharedFlow<Throwable>

    /** Cancels all scopes managed by this module. */
    fun destroy()
}
