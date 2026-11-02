package com.lanraragi.reader.archive

import com.lanraragi.reader.ServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * Runs the server writes of a gallery-list batch (delete, add to a category
 * or tank, clear "new", tank sort/delete/download) on the process scope, so a
 * batch the user started runs to its end even when the list screen that
 * started it closes: a delete or membership batch cut in half would leave the
 * server half-changed. The screen only awaits the outcome; cancelling that
 * wait never cancels the writes.
 *
 * @param scope the long-lived scope the writes run on (Main-dispatched; the
 *   writes switch to IO themselves)
 */
class ListBatchWrites(
    private val scope: CoroutineScope = ServiceRegistry.coroutineModule.applicationScope,
) {

    /** Runs [block] on the process scope and waits for its result. */
    suspend fun <T> run(block: suspend CoroutineScope.() -> T): T = scope.async(block = block).await()
}
