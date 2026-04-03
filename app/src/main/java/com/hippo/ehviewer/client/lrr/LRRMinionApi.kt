package com.hippo.ehviewer.client.lrr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for LANraragi Minion (background job) operations.
 *
 * Endpoints:
 * - GET    /api/minion/:jobid  — Get status of a specific job
 * - DELETE /api/minion/jobs    — Clear the job queue (non-standard, may not exist)
 */
object LRRMinionApi {

    @Serializable
    data class MinionJobStatus(
        val state: String = "",
        val task: String = "",
        val error: String = "",
        val notes: String = ""
    )

    /**
     * GET /api/minion/:jobid — Get status of a background job.
     */
    @JvmStatic
    suspend fun getJobStatus(
        client: OkHttpClient,
        baseUrl: String,
        jobId: String
    ): MinionJobStatus = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("api")
            .addPathSegment("minion")
            .addPathSegment(jobId)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<MinionJobStatus>(body)
        }
    }

    /**
     * DELETE /api/minion/jobs — Clear the job queue.
     * ⚠️ DEPRECATED: This endpoint is NOT in the LANraragi OpenAPI spec
     * and may be removed in future server versions. Currently unused.
     */
    @Deprecated("Non-standard endpoint, not in OpenAPI spec")
    @JvmStatic
    suspend fun clearJobs(
        client: OkHttpClient,
        baseUrl: String
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/minion/jobs")
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }
}
