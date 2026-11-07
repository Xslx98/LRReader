package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.data.LRRMinionJob
import com.lanraragi.reader.client.api.data.LRRPluginInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for server-side metadata plugins. Every call here needs the API
 * key, which the shared client's auth interceptor sends as a header; the
 * server's `key` query parameter is never used because it would land in logs.
 *
 * Endpoints:
 * - GET  /api/plugins/metadata    — List metadata plugins
 * - POST /api/plugins/queue       — Run a plugin in a Minion job (writes nothing to the archive)
 * - GET  /api/minion/{id}/detail  — Read a job, including its result
 */
object LRRPluginApi {

    /** The bodies decode from a blocking stream. */
    private suspend fun <T> onIo(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend () -> T,
    ): T = withContext(dispatcher) { block() }

    /** GET /api/plugins/metadata. */
    suspend fun listMetadataPlugins(client: OkHttpClient, baseUrl: String): List<LRRPluginInfo> = retryOnFailure {
        onIo {
            val url = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/plugins/metadata")
                .build()
            client.newCall(Request.Builder().url(url).get().build()).await().use { response ->
                ensureSuccess(response)
                decodeJsonBody(response)
            }
        }
    }

    /**
     * POST /api/plugins/queue. Not retried: a retry after a lost response
     * would run the plugin, and whatever external site it calls, again.
     *
     * @param arg the plugin's one-shot argument; blank is left off the request
     * @return the Minion job id
     */
    suspend fun queuePlugin(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String,
        pluginNamespace: String,
        arg: String?,
    ): Long = onIo {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/plugins/queue")
            .addQueryParameter("id", requireValidArcid(arcid))
            .addQueryParameter("plugin", pluginNamespace)
            .apply { if (!arg.isNullOrBlank()) addQueryParameter("arg", arg) }
            .build()
        client.newCall(Request.Builder().url(url).post(EMPTY_REQUEST_BODY).build()).await().use { response ->
            ensureSuccess(response)
            val body: JsonObject = decodeJsonBody(response)
            (body["job"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                ?: throw LRRMissingFieldException("job")
        }
    }

    /** GET /api/minion/{id}/detail. `/api/minion/{id}` has no result, so it cannot replace this. */
    suspend fun getMinionJob(client: OkHttpClient, baseUrl: String, jobId: Long): LRRMinionJob = retryOnFailure {
        onIo {
            val url = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/minion")
                .addPathSegment(jobId.toString())
                .addPathSegment("detail")
                .build()
            client.newCall(Request.Builder().url(url).get().build()).await().use { response ->
                ensureSuccess(response)
                LRRMinionJob(decodeJsonBody<JsonObject>(response))
            }
        }
    }
}
