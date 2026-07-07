package com.lanraragi.reader.client.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

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
 * - GET    /api/archives/:id/stamps         — List 1-indexed pages that have stamps
 * - GET    /api/archives/:id/stamps/:page   — List stamps on one 1-indexed page
 * - PUT    /api/archives/:id/stamps/:page   🔑 — Add a stamp, returns the new stamp id
 * - PUT    /api/stamps/:id                  🔑 — Update a stamp's content and/or position
 * - DELETE /api/stamps/:id                  🔑 — Delete a stamp
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
        client.newCall(request).await().use { response ->
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
        client.newCall(request).await().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<PageStampsResult>(body).result
        }
    }

    @Serializable
    private data class AddStampResult(
        @SerialName("stamp_id") val stampId: String? = null,
        val success: Int = 0,
        val error: String? = null,
    )

    /**
     * Fast-fail on blank stamp ids before a doomed request leaves the device.
     * Stamp ids are opaque strings per the API contract (currently
     * `STAMPS_<page>_<timestampMs>`, but the server format could evolve), so
     * no format validation beyond non-blank.
     */
    private fun requireNonBlankStampId(stampId: String): String {
        if (stampId.isBlank()) {
            throw LRRClientValidationException("stampId must not be blank")
        }
        return stampId
    }

    /**
     * PUT /api/archives/:id/stamps/:page — add a stamp, returns the new stamp id.
     *
     * @param page1 1-indexed page number.
     */
    @JvmStatic
    suspend fun addStamp(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String,
        page1: Int,
        content: String,
        position: String,
    ): String = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("stamps")
            .addPathSegment(page1.toString())
            .addQueryParameter("content", content)
            .addQueryParameter("position", position)
            .build()
        val request = Request.Builder()
            .url(url)
            .put(EMPTY_REQUEST_BODY)
            .build()
        client.newCall(request).await().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            val parsed = lrrJson.decodeFromString<AddStampResult>(body)
            if (parsed.success != 1) {
                // Surface the server's in-body error (2xx + {success:0, error:"…"}
                // envelope), matching the LRRArchiveApi delete/upload precedent.
                throw IOException(parsed.error ?: "add_stamp failed")
            }
            if (parsed.stampId.isNullOrEmpty()) {
                // Contract violation: success:1 without a stamp_id.
                throw LRRMissingFieldException("stamp_id")
            }
            parsed.stampId
        }
    }

    /**
     * PUT /api/stamps/:id — update content and/or position (only non-null params sent).
     * [stampId] is the full stamp key (e.g. `STAMPS_2_1719999999999`), not an arcid.
     */
    @JvmStatic
    suspend fun updateStamp(
        client: OkHttpClient,
        baseUrl: String,
        stampId: String,
        content: String? = null,
        position: String? = null,
    ) {
        require(content != null || position != null) { "updateStamp needs content or position" }
        withContext(Dispatchers.IO) {
            val urlBuilder = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/stamps")
                .addPathSegment(requireNonBlankStampId(stampId))
            if (content != null) urlBuilder.addQueryParameter("content", content)
            if (position != null) urlBuilder.addQueryParameter("position", position)
            val request = Request.Builder()
                .url(urlBuilder.build())
                .put(EMPTY_REQUEST_BODY)
                .build()
            client.newCall(request).await().use { response -> ensureSuccess(response) }
        }
    }

    /**
     * DELETE /api/stamps/:id. [stampId] is the full stamp key, not an arcid.
     */
    @JvmStatic
    suspend fun deleteStamp(client: OkHttpClient, baseUrl: String, stampId: String) {
        withContext(Dispatchers.IO) {
            val url = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/stamps")
                .addPathSegment(requireNonBlankStampId(stampId))
                .build()
            val request = Request.Builder()
                .url(url)
                .delete()
                .build()
            client.newCall(request).await().use { response -> ensureSuccess(response) }
        }
    }
}
