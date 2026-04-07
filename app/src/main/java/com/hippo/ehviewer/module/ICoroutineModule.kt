package com.hippo.ehviewer.module

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
     * Observable stream of uncaught coroutine exceptions. UI layers can subscribe to
     * surface error notifications.
     */
    val uncaughtErrors: SharedFlow<Throwable>

    /** Cancels all scopes managed by this module. */
    fun destroy()
}
