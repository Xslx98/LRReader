@file:JvmName("CoroutineBridge")
package com.hippo.ehviewer.util

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.ServiceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bridge for Java Scene/Activity classes to run blocking work on [Dispatchers.IO]
 * with lifecycle-aware cancellation via [lifecycleScope].
 *
 * Replaces the pattern of calling a `runBlocking` bridge on the main thread.
 * Java callers use:
 * ```java
 * CoroutineBridge.launchIO(getViewLifecycleOwner(), () -> {
 *     // run blocking work on Dispatchers.IO
 * });
 * ```
 *
 * **Design references:**
 * - lifecycleScope auto-cancels when Lifecycle is DESTROYED
 *   (https://developer.android.com/topic/libraries/architecture/coroutines)
 * - CoroutineExceptionHandler from [CoroutineModule] logs uncaught exceptions
 *   (https://kotlinlang.org/docs/exception-handling.html)
 */

/**
 * Run [task] on [Dispatchers.IO], scoped to [owner]'s lifecycle.
 * Fire-and-forget — no result returned to caller.
 */
fun launchIO(owner: LifecycleOwner, task: Runnable) {
    owner.lifecycleScope.launch(
        ServiceRegistry.coroutineModule.exceptionHandler
    ) {
        withContext(Dispatchers.IO) { task.run() }
    }
}

/**
 * Run [task] on [Dispatchers.IO], then deliver its result to [onResult] on the main thread.
 * Both [task] and [onResult] are scoped to [owner]'s lifecycle.
 */
fun <T> launchIO(
    owner: LifecycleOwner,
    task: java.util.concurrent.Callable<T>,
    onResult: java.util.function.Consumer<T>
) {
    owner.lifecycleScope.launch(
        ServiceRegistry.coroutineModule.exceptionHandler
    ) {
        val result = withContext(Dispatchers.IO) { task.call() }
        onResult.accept(result)
    }
}

/**
 * Run [task] on [Dispatchers.IO] using the application-scoped coroutine.
 * Use when no [LifecycleOwner] is available (e.g., EhCallback network callbacks
 * where the originating Fragment may already be destroyed).
 */
fun launchIOGlobal(task: Runnable) {
    ServiceRegistry.coroutineModule.ioScope.launch {
        task.run()
    }
}
