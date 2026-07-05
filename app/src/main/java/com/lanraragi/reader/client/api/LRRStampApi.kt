package com.lanraragi.reader.client.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for LANraragi Stamp operations (server 0.9.8+).
 *
 * Stamps are text annotations pinned to a normalized (0-100) position on one
 * page. Page params here are 1-INDEXED per server semantics (the stamp key
 * embeds the page: `STAMPS_<page>_<timestampMs>`); callers convert from the
 * reader's 0-indexed pages. Older servers have no stamps routes and answer
 * 404 — callers treat [LRRHttpException] with `code == 404` from
 * [getStampedPages] as "server does not support stamps".
 *
 * Endpoints:
 * - GET /api/archives/:id/stamps         — List 1-indexed pages that have stamps
 * - GET /api/archives/:id/stamps/:page   — List stamps on one 1-indexed page
 */
object LRRStampApi {

    @Serializable
    data class StampData(
        val id: String = "",
        /** Raw "x,y" percentage string (0-100, floats); may be malformed. */
        val position: String = "",
        val content: String = ""
    )

    @Serializable
    private data class StampedPagesResult(val result: List<String> = emptyList())

    @Serializable
    private data class PageStampsResult(val result: List<StampData> = emptyList())

    /**
     * GET /api/archives/:id/stamps — 1-indexed pages that contain stamps.
     * Entries that aren't parseable as integers are skipped.
     */
    @JvmStatic
    suspend fun getStampedPages(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String
    ): List<Int> = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("stamps")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<StampedPagesResult>(body)
                .result.mapNotNull { it.toIntOrNull() }
        }
    }

    /**
     * GET /api/archives/:id/stamps/:page — stamps on one page.
     *
     * @param page1 1-indexed page number.
     */
    @JvmStatic
    suspend fun getStampsByPage(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String,
        page1: Int
    ): List<StampData> = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("stamps")
            .addPathSegment(page1.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<PageStampsResult>(body).result
        }
    }
}
