package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.data.LRRSearchResult
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
            if (!category.isNullOrEmpty()) urlBuilder.addQueryParameter("category", requireValidCategoryId(category))
            if (start > 0) urlBuilder.addQueryParameter("start", start.toString())
            if (!sortby.isNullOrEmpty()) urlBuilder.addQueryParameter("sortby", sortby)
            if (!order.isNullOrEmpty()) urlBuilder.addQueryParameter("order", order)
            if (newonly) urlBuilder.addQueryParameter("newonly", "true")
            if (untaggedonly) urlBuilder.addQueryParameter("untaggedonly", "true")
            // Always send groupby_tanks: the server defaults it to true, which folds
            // Tankoubons into results as 15-char TANK_ ids the archive pipeline can't render.
            urlBuilder.addQueryParameter("groupby_tanks", groupbyTanks.toString())

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
        if (!category.isNullOrEmpty()) urlBuilder.addQueryParameter("category", requireValidCategoryId(category))
        if (newonly) urlBuilder.addQueryParameter("newonly", "true")
        if (untaggedonly) urlBuilder.addQueryParameter("untaggedonly", "true")
        // Always send groupby_tanks (see searchArchives): server defaults it to true.
        urlBuilder.addQueryParameter("groupby_tanks", groupbyTanks.toString())

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
