package com.lanraragi.reader.client.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for LANraragi Minion (background job) operations.
 *
 * Endpoints (per OpenAPI v0.9.6):
 * - GET /api/minion/{jobid} — Get the basic status of a Minion job (operationId
 *   `minionJobStatus`). The `jobid` path parameter is typed as `integer` in
 *   the spec, hence the [Long] argument here.
 */
object LRRMinionApi {

    /**
     * Response shape for `GET /minion/{jobid}` (operationId `minionJobStatus`).
     *
     * Per the OpenAPI v0.9.6 spec:
     * - `state` is one of `inactive | active | finished | failed`
     * - `task` identifies the Minion job kind (e.g. `handle_upload`)
     * - `error` is a nullable string set when the job failed
     * - `notes` is an arbitrary, nullable JSON object whose payload is
     *   task-specific (the spec example shows `{"example_note": "..."}`).
     *   The previous String typing would throw `JsonDecodingException`
     *   on any non-empty notes object.
     */
    @Serializable
    data class MinionJobStatus(
        val state: String = "",
        val task: String = "",
        val error: String? = null,
        val notes: JsonElement? = null,
    )

    /**
     * GET /api/minion/{jobid} — Get status of a background job. The spec
     * declares `jobid` as `integer`; we accept a [Long] so callers can pass
     * upstream Minion job IDs (e.g. the `job` field of a `downloadUrl`
     * response) without a separate `.toString()` round-trip.
     */
    @JvmStatic
    suspend fun getJobStatus(
        client: OkHttpClient,
        baseUrl: String,
        jobId: Long
    ): MinionJobStatus = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("minion")
            .addPathSegment(jobId.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).await().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<MinionJobStatus>(body)
        }
    }
}
