package com.hippo.ehviewer.client.lrr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

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
     * GET /api/database/stats — Get tag statistics.
     */
    @JvmStatic
    suspend fun getDatabaseStats(
        client: OkHttpClient,
        baseUrl: String
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/database/stats")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            response.body?.string()
                ?: throw IOException("服务器返回空响应体")
        }
    }

    @JvmStatic
    suspend fun getDatabaseStats(): String =
        getDatabaseStats(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl())
}

/**
 * API class for LANraragi Shinobu (file watcher) operations.
 *
 * Endpoints:
 * - GET  /api/shinobu         — Get status
 * - POST /api/shinobu/stop    — Stop watcher (future)
 * - POST /api/shinobu/restart — Restart watcher
 */
object LRRShinobuApi {

    /**
     * GET /api/shinobu — Get Shinobu (file watcher) status.
     */
    @JvmStatic
    suspend fun getShinobuStatus(
        client: OkHttpClient,
        baseUrl: String
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/shinobu")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            response.body?.string()
                ?: throw IOException("服务器返回空响应体")
        }
    }

    /**
     * POST /api/shinobu/restart — Restart Shinobu.
     */
    @JvmStatic
    suspend fun restartShinobu(
        client: OkHttpClient,
        baseUrl: String
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/shinobu/restart")
            .post(EMPTY_REQUEST_BODY)
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }

    // ==================== Simplified overloads ====================

    @JvmStatic
    suspend fun getShinobuStatus(): String =
        getShinobuStatus(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl())

    @JvmStatic
    suspend fun restartShinobu() =
        restartShinobu(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl())
}
