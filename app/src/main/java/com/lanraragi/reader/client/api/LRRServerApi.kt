package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.data.LRRServerInfo
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
                val url = parseBaseUrl(baseUrl).newBuilder()
                    .addPathSegments("api/info")
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    ensureSuccess(response)
                    val body = response.body?.string()
                        ?: throw LRREmptyBodyException()
                    lrrJson.decodeFromString<LRRServerInfo>(body)
                }
            }
        }
}
