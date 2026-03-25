@file:JvmName("LRRCoroutineHelper")
package com.hippo.ehviewer.client.lrr

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
 */
fun <T> runSuspend(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
    runBlocking { block() }
