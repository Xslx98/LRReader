@file:JvmName("LRRCoroutineHelper")
package com.lanraragi.reader.client.api

import androidx.annotation.WorkerThread
import kotlinx.coroutines.runBlocking

/**
 * Utility for calling Kotlin suspend functions from Java code without
 * `BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, ...)` boilerplate.
 *
 * Java usage:
 * ```java
 * LRRArchive archive = (LRRArchive) LRRCoroutineHelper.runSuspend((scope, cont) ->
 *     LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcid, cont));
 * ```
 *
 * This is equivalent to `runBlocking { ... }` but exposed as a static method
 * that Java can call via SAM conversion of the `suspend CoroutineScope.() -> T` block.
 *
 * IMPORTANT: Only call this on a background thread. Never call from the main thread.
 * The @WorkerThread annotation enables lint enforcement at call sites.
 *
 * IMPORTANT: This is a JAVA-ONLY bridge. Kotlin code that is already inside a
 * coroutine (lifecycleScope/viewModelScope launch, suspend functions) must call
 * the suspend API directly — wrapping it here blocks the worker thread inside
 * runBlocking AND breaks structured cancellation (runBlocking starts a new root
 * scope, so cancelling the outer job no longer cancels the wrapped call).
 */
@WorkerThread
fun <T> runSuspend(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T {
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
        "runSuspend() must not be called on the main thread — use lifecycleScope.launch(Dispatchers.IO) instead"
    }
    return runBlocking { block() }
}
