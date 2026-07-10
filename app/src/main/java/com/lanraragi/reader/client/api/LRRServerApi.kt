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
     * GET /api/info — Test connection and get server info. Auth (when the
     * server protects /api/info) comes from LRRAuthInterceptor on the caller's
     * client.
     */
    @JvmStatic
    suspend fun getServerInfo(client: OkHttpClient, baseUrl: String): LRRServerInfo =
        getServerInfo(client, baseUrl, null)

    /**
     * GET /api/info — connection-testing variant with explicit auth.
     *
     * LANraragi protects /api/info when "Enable Password/API protection for
     * all API access" is on. Test clients strip LRRAuthInterceptor (see
     * [LRRUrlHelper.buildTestClient]), so candidate-server probes never depend
     * on process-global auth state (NET-7); the candidate key rides the
     * request itself, in the interceptor's token format.
     */
    @JvmStatic
    suspend fun getServerInfo(
        client: OkHttpClient,
        baseUrl: String,
        apiKey: String?
    ): LRRServerInfo =
        retryOnFailure {
            withContext(Dispatchers.IO) {
                val url = parseBaseUrl(baseUrl).newBuilder()
                    .addPathSegments("api/info")
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply {
                        if (!apiKey.isNullOrEmpty()) {
                            header("Authorization", bearerAuthHeaderValue(apiKey))
                        }
                    }
                    .build()
                client.newCall(request).await().use { response ->
                    ensureSuccess(response)
                    val body = response.body?.string()
                        ?: throw LRREmptyBodyException()
                    val info = lrrJson.decodeFromString<LRRServerInfo>(body)
                    // Record the progress-tracking capability so updateProgress can
                    // honor the spec's "check /api/info first" guidance per server.
                    ServerCapabilityCache.setTracksProgress(baseUrl, info.serverTracksProgress)
                    info
                }
            }
        }
}
