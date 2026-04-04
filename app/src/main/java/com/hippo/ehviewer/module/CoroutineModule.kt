package com.hippo.ehviewer.module

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
class CoroutineModule {

    private val tag = "CoroutineModule"

    /**
     * Global exception handler that logs uncaught coroutine exceptions.
     * Installed on all scopes created by this module.
     */
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Uncaught coroutine exception", throwable)
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
    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler
    )

    /**
     * IO-scoped [CoroutineScope] for background work (network, database, file I/O).
     * Backed by [SupervisorJob] so individual task failures don't cancel the scope.
     */
    val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + exceptionHandler
    )

    fun destroy() {
        applicationScope.cancel()
        ioScope.cancel()
    }
}
