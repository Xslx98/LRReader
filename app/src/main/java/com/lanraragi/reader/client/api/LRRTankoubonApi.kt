package com.lanraragi.reader.client.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for LANraragi Tankoubon operations.
 *
 * Tankoubons are ordered collections of archives (like volumes or reading lists).
 *
 * Endpoints:
 * - GET /api/tankoubons — List all tankoubons (paginated)
 */
object LRRTankoubonApi {

    @Serializable
    data class Tankoubon(
        val id: String = "",
        val name: String = "",
        val archives: List<String> = emptyList()
    )

    @Serializable
    data class TankoubonListResult(
        val result: List<Tankoubon> = emptyList()
    )

    /**
     * GET /api/tankoubons — List all tankoubons (paginated).
     *
     * @param page Page number for pagination (optional)
     */
    @JvmStatic
    suspend fun getTankoubons(
        client: OkHttpClient,
        baseUrl: String,
        page: Int? = null
    ): TankoubonListResult = withContext(Dispatchers.IO) {
        val urlBuilder = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/tankoubons")
        if (page != null && page > 0) urlBuilder.addQueryParameter("page", page.toString())

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<TankoubonListResult>(body)
        }
    }
}
