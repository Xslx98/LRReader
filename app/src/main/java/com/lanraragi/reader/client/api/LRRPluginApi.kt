package com.lanraragi.reader.client.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for LANraragi plugin operations.
 *
 * Endpoints:
 * - GET  /api/plugins/:type     — List available plugins by type
 * - POST /api/plugins/:id/run   — Execute a plugin on an archive
 */
object LRRPluginApi {

    @Serializable
    data class PluginInfo(
        val name: String = "",
        val namespace: String = "",
        val type: String = "",
        val description: String = "",
        val icon: String = "",
        val oneshot_arg: String = "",
        val parameters: List<PluginParameter> = emptyList()
    )

    @Serializable
    data class PluginParameter(
        val type: String = "",
        val desc: String = ""
    )

    @Serializable
    data class PluginRunResult(
        val success: Int = 0,
        val message: String = "",
        val data: String = ""
    )

    /**
     * GET /api/plugins/:type — List available plugins.
     *
     * @param type Plugin type: "metadata", "script", "download", "login"
     */
    @JvmStatic
    suspend fun getPlugins(
        client: OkHttpClient,
        baseUrl: String,
        type: String
    ): List<PluginInfo> = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("plugins")
            .addPathSegment(type)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<List<PluginInfo>>(body)
        }
    }

    /**
     * POST /api/plugins/use — Execute a plugin (OpenAPI spec compliant).
     *
     * @param namespace Plugin namespace (e.g., "chaika")
     * @param archiveId Archive ID to run the plugin on (optional for some plugins)
     * @param arg One-shot argument (optional)
     */
    @JvmStatic
    suspend fun runPlugin(
        client: OkHttpClient,
        baseUrl: String,
        namespace: String,
        archiveId: String? = null,
        arg: String? = null
    ): PluginRunResult = withContext(Dispatchers.IO) {
        val urlBuilder = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/plugins/use")
            .addQueryParameter("plugin", namespace)
        if (!archiveId.isNullOrEmpty()) urlBuilder.addQueryParameter("id", archiveId)
        if (!arg.isNullOrEmpty()) urlBuilder.addQueryParameter("arg", arg)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .post(EMPTY_REQUEST_BODY)
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<PluginRunResult>(body)
        }
    }
}
