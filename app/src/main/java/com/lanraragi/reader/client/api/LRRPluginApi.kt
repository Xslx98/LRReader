package com.lanraragi.reader.client.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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

    /**
     * Response shape for `POST /plugins/use` (operationId `usePluginSync`).
     *
     * Per the OpenAPI v0.9.6 spec:
     * - `operation` is always the string `"use_plugin"`
     * - `type` is the plugin category (download / login / metadata / script),
     *   nullable for plugins that do not declare one
     * - `success` is a 0/1 integer flag
     * - `error` is a nullable string that is set when `success == 0`
     * - `data` is an arbitrary JSON object whose shape is plugin-defined
     *   (e.g. `{"new_tags": "pages:45"}` for a metadata plugin) — must be
     *   typed as a generic [JsonElement] to deserialise without locking the
     *   client to one specific plugin's schema. The previous String-typed
     *   field would throw `JsonDecodingException` on every spec-compliant
     *   response.
     */
    @Serializable
    data class PluginRunResult(
        val operation: String = "",
        val type: String? = null,
        val success: Int = 0,
        val error: String? = null,
        val data: JsonElement? = null,
    )

    /**
     * Plugin type categories accepted by `GET /plugins/{type}` per the
     * OpenAPI spec's `type` path-parameter enum. Use `ALL` to list every
     * registered plugin regardless of category.
     */
    enum class PluginType(val wireValue: String) {
        DOWNLOAD("download"),
        LOGIN("login"),
        METADATA("metadata"),
        SCRIPT("script"),
        ALL("all"),
    }

    private val pluginTypeWireValues: Set<String> =
        PluginType.entries.map { it.wireValue }.toSet()

    /**
     * GET /api/plugins/{type} — List available plugins.
     *
     * @param type Plugin type — must be one of the spec-defined wire values
     *   (`download`, `login`, `metadata`, `script`, `all`). Prefer the
     *   typed [getPlugins] overload that takes a [PluginType] enum below.
     * @throws LRRClientValidationException when [type] is not in the spec enum
     */
    @JvmStatic
    suspend fun getPlugins(
        client: OkHttpClient,
        baseUrl: String,
        type: String
    ): List<PluginInfo> = withContext(Dispatchers.IO) {
        if (type !in pluginTypeWireValues) {
            throw LRRClientValidationException(
                "Invalid plugin type '$type'. Spec enum: $pluginTypeWireValues"
            )
        }
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
     * Typed overload of [getPlugins] that cannot be called with an out-of-spec
     * value at the call site. Prefer this for new call sites; the String
     * overload is kept for legacy interop and validates at runtime.
     */
    @JvmStatic
    suspend fun getPlugins(
        client: OkHttpClient,
        baseUrl: String,
        type: PluginType
    ): List<PluginInfo> = getPlugins(client, baseUrl, type.wireValue)

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
        if (!archiveId.isNullOrEmpty()) urlBuilder.addQueryParameter("id", requireValidArcid(archiveId))
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
