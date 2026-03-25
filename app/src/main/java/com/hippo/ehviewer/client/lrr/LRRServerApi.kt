package com.hippo.ehviewer.client.lrr

import com.hippo.ehviewer.client.lrr.data.LRRServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for LANraragi server info operations.
 *
 * Endpoints:
 * - GET /api/info — Test connection and get server info
 */
object LRRServerApi {

    /**
     * GET /api/info — Test connection and get server info.
     * This endpoint does NOT require authentication.
     */
    @JvmStatic
    suspend fun getServerInfo(client: OkHttpClient, baseUrl: String): LRRServerInfo =
        retryOnFailure {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$baseUrl/api/info")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    ensureSuccess(response)
                    val body = response.body?.string()
                        ?: throw java.io.IOException("服务器返回空响应体")
                    lrrJson.decodeFromString<LRRServerInfo>(body)
                }
            }
        }
}
