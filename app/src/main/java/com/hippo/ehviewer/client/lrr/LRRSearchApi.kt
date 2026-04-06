package com.hippo.ehviewer.client.lrr

import com.hippo.ehviewer.client.lrr.data.LRRSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for LANraragi search operations.
 *
 * Endpoints:
 * - GET /api/search — Search archives
 * - GET /api/search/random — Get random archives
 */
object LRRSearchApi {

    /**
     * GET /api/search — Search archives.
     */
    @JvmStatic
    suspend fun searchArchives(
        client: OkHttpClient,
        baseUrl: String,
        filter: String?,
        category: String?,
        start: Int,
        sortby: String?,
        order: String?,
        newonly: Boolean,
        untaggedonly: Boolean = false,
        groupbyTanks: Boolean = false
    ): LRRSearchResult = retryOnFailure {
        withContext(Dispatchers.IO) {
            val urlBuilder = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/search")
            if (!filter.isNullOrEmpty()) urlBuilder.addQueryParameter("filter", filter)
            if (!category.isNullOrEmpty()) urlBuilder.addQueryParameter("category", category)
            if (start > 0) urlBuilder.addQueryParameter("start", start.toString())
            if (!sortby.isNullOrEmpty()) urlBuilder.addQueryParameter("sortby", sortby)
            if (!order.isNullOrEmpty()) urlBuilder.addQueryParameter("order", order)
            if (newonly) urlBuilder.addQueryParameter("newonly", "true")
            if (untaggedonly) urlBuilder.addQueryParameter("untaggedonly", "true")
            if (groupbyTanks) urlBuilder.addQueryParameter("groupby_tanks", "true")

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                ensureSuccess(response)
                val body = response.body?.string()
                    ?: throw LRREmptyBodyException()
                lrrJson.decodeFromString<LRRSearchResult>(body)
            }
        }
    }

    /**
     * GET /api/search/random — Get random archives.
     */
    @JvmStatic
    suspend fun getRandomArchives(
        client: OkHttpClient,
        baseUrl: String,
        filter: String?,
        count: Int,
        category: String? = null,
        newonly: Boolean = false,
        untaggedonly: Boolean = false,
        groupbyTanks: Boolean = false
    ): LRRSearchResult = withContext(Dispatchers.IO) {
        val urlBuilder = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/search/random")
        if (!filter.isNullOrEmpty()) urlBuilder.addQueryParameter("filter", filter)
        if (count > 0) urlBuilder.addQueryParameter("count", count.toString())
        if (!category.isNullOrEmpty()) urlBuilder.addQueryParameter("category", category)
        if (newonly) urlBuilder.addQueryParameter("newonly", "true")
        if (untaggedonly) urlBuilder.addQueryParameter("untaggedonly", "true")
        if (groupbyTanks) urlBuilder.addQueryParameter("groupby_tanks", "true")

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<LRRSearchResult>(body)
        }
    }

    // ==================== Simplified overloads (use LRRClientProvider) ====================

    /**
     * Simplified searchArchives using [LRRClientProvider].
     */
    @JvmStatic
    suspend fun searchArchives(
        filter: String?,
        category: String?,
        start: Int,
        sortby: String?,
        order: String?,
        newonly: Boolean
    ): LRRSearchResult = searchArchives(
        LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(),
        filter, category, start, sortby, order, newonly
    )

    /**
     * Simplified getRandomArchives using [LRRClientProvider].
     */
    @JvmStatic
    suspend fun getRandomArchives(
        filter: String?,
        count: Int
    ): LRRSearchResult = getRandomArchives(
        LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(),
        filter, count
    )
}
