package com.lanraragi.reader.client.api.data

import com.lanraragi.reader.domain.MetadataPlugin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One entry of `GET /api/plugins/metadata`. `parameters` (whose shape varies
 * and which may carry saved settings) and `icon` are left undeclared, so they
 * are skipped while decoding and never held in memory. [version] is a
 * primitive because a plugin's Perl source decides whether it renders as a
 * number or a string.
 */
@Serializable
class LRRPluginInfo(
    val name: String = "",
    val namespace: String = "",
    val description: String = "",
    val version: JsonPrimitive = JsonNull,
    @SerialName("oneshot_arg") val oneshotArg: String? = null,
    @SerialName("login_from") val loginFrom: String? = null,
) {
    fun toMetadataPlugin() = MetadataPlugin(
        namespace = namespace,
        name = name.ifBlank { namespace },
        version = version.contentOrNull.orEmpty(),
        description = description,
        oneshotLabel = oneshotArg?.takeIf { it.isNotBlank() },
        loginFrom = loginFrom?.takeIf { it.isNotBlank() },
    )
}

/** Any primitive as text, null otherwise: a plugin's title of `1984` may render as a number. */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * `GET /api/minion/{id}/detail`, reduced to what a plugin run needs. [result]
 * is absent until the job ends, an object when the task finished, and a plain
 * string when the job itself died. These shapes are read by hand because their
 * fields vary by plugin, and to keep generated serializers out of the APK.
 */
class LRRMinionJob(body: JsonObject) {
    val state: String = body.text("state").orEmpty()
    private val result: JsonElement? = body["result"]

    /** The task's report, once it finished. */
    fun pluginResult(): LRRPluginResult? = (result as? JsonObject)?.let { LRRPluginResult(it) }

    /** The server's message for a job that died outside the plugin. */
    fun failureText(): String? = (result as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * The `{type, success, error, data}` report a plugin run gives, at HTTP 200
 * even when the plugin failed: success comes from this body, not the status.
 */
class LRRPluginResult(body: JsonObject) {
    val success: Boolean = (body["success"] as? JsonPrimitive).let { it?.booleanOrNull ?: ((it?.intOrNull ?: 0) != 0) }
    val error: String? = body.text("error")
    val data: LRRPluginData? = (body["data"] as? JsonObject)?.let { LRRPluginData(it) }
}

/** A successful run's findings. [newTags] looks like `" a, b"`. */
class LRRPluginData(body: JsonObject) {
    val newTags: String? = body.text("new_tags")
    val titleText: String? = body.text("title")?.trim()?.takeIf { it.isNotEmpty() }
    val summaryText: String? = body.text("summary")?.trim()?.takeIf { it.isNotEmpty() }
}
