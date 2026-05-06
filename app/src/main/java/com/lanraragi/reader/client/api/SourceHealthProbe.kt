package com.lanraragi.reader.client.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Quick liveness probe for a LANraragi server.
 *
 * Detail-page UX problem this solves: when the metadata fetch fails
 * with HTTP 400 or 404, we cannot tell from the status code alone
 * whether
 *  (a) the archive truly does not exist on the server (LANraragi
 *      returns 400 "Invalid id" for unknown arcids), or
 *  (b) a reverse proxy in front of an offline backend is responding
 *      400 / 502 itself.
 *
 * The two cases warrant different UX (`已删除` vs `不可达`). We
 * disambiguate by probing the unauthenticated `GET /api/info`
 * endpoint with a short per-call deadline. A 200 means the server
 * is alive and the archive really is missing; any other outcome
 * (non-2xx, exception, timeout) means we should not promise the
 * user that the archive was deleted.
 *
 * @param client shared OkHttp client (interceptor chain reused so
 *   the cleartext / auth checks still run).
 * @param baseUrl source server base URL — the same URL the failing
 *   metadata request was issued against.
 * @param totalTimeoutMs overall deadline for the probe; tuned to
 *   stay well under the 10s connect timeout so the probe never
 *   makes "archive deleted" feel slower than the original failure.
 *   Default 3000ms balances LAN latency against wakeup jitter.
 * @return true iff the server returned a 2xx response within the
 *   deadline; false on any failure mode.
 */
suspend fun probeSourceHealthy(
    client: OkHttpClient,
    baseUrl: String,
    totalTimeoutMs: Long = 3000L,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/info")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val call = client.newCall(request)
        // Per-call deadline, distinct from the per-client connect /
        // read / write timeouts. Bounds the whole probe at the budget
        // above regardless of where it gets stuck.
        call.timeout().timeout(totalTimeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { it.isSuccessful }
    }.getOrElse { false }
}
