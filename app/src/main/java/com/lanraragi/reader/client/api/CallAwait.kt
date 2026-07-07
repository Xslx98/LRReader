package com.lanraragi.reader.client.api

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Suspends until the call completes, propagating coroutine cancellation to
 * the underlying OkHttp call (audit finding NET-3).
 *
 * - Backed by [Call.enqueue], so the waiting coroutine does not block an IO
 *   dispatcher thread while the request is in flight.
 * - On coroutine cancellation, [Call.cancel] severs the connection and the
 *   await site throws [kotlinx.coroutines.CancellationException]; the
 *   IOException("Canceled") that OkHttp subsequently reports resumes an
 *   already-cancelled continuation and is dropped.
 * - If the response lands while cancellation races in, the onCancellation
 *   block closes it so the connection is not leaked.
 * - After resume, reading the body (`.use { … }`) is outside the cancellable
 *   window — accepted for KB-sized JSON metadata bodies. Streaming paths
 *   (reader page cache, download worker) instead hold the [Call] and cancel
 *   it from lifecycle hooks.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, resp, _ -> runCatching { resp.close() } }
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
}
