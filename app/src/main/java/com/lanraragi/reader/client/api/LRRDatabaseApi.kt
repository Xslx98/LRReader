package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.data.LRRTagStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for LANraragi database operations.
 *
 * Endpoints:
 * - GET  /api/database/stats  — Get tag statistics
 * - GET  /api/database/backup — Download database backup (future)
 * - POST /api/database/clean  — Clean orphaned entries (future)
 */
object LRRDatabaseApi {

    /**
     * The /api/database/stats response can be several MB on large libraries;
     * the shared client's 30s callTimeout would truncate it on slow links.
     * The shared large-file client has no call cap (and a 60s readTimeout
     * instead of the inherited 10s — a deliberate loosening that's safe in
     * the lenient direction for a streamed body).
     */
    private fun longCallClient(): OkHttpClient =
        com.lanraragi.reader.ServiceRegistry.networkModule.largeFileClient

    /**
     * GET /api/database/stats — Get tag statistics.
     */
    @JvmStatic
    suspend fun getDatabaseStats(
        client: OkHttpClient,
        baseUrl: String
    ): String = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/database/stats")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).await().use { response ->
            ensureSuccess(response)
            response.body?.string()
                ?: throw LRREmptyBodyException()
        }
    }

    @JvmStatic
    suspend fun getDatabaseStats(): String =
        getDatabaseStats(longCallClient(), LRRClientProvider.getBaseUrl())

    /**
     * GET /api/database/stats — Get tag statistics as typed objects.
     */
    @JvmStatic
    suspend fun getTagStats(
        client: OkHttpClient,
        baseUrl: String
    ): List<LRRTagStat> = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/database/stats")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).await().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<List<LRRTagStat>>(body)
        }
    }

    @JvmStatic
    suspend fun getTagStats(): List<LRRTagStat> =
        getTagStats(longCallClient(), LRRClientProvider.getBaseUrl())
}
