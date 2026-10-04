package com.lanraragi.reader.updater

import android.util.Log
import com.lanraragi.reader.client.api.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * One entry of `advisory.json` at the root of the repository's main branch
 * (audit 2026-10-04 C46): a notice for installed versions with a known
 * problem, e.g. "v1.28.0 can lose download progress; update to v1.28.1".
 * Android cannot roll an app back, so this is the only way to reach users of a
 * bad build before (or without) a new APK.
 *
 * ```json
 * { "advisories": [ {
 *     "id": "2026-10-28-progress",
 *     "min_version_code": 12800, "max_version_code": 12800,
 *     "message": { "en": "…", "zh": "…" },
 *     "url": "https://github.com/Xslx98/LRReader/releases/tag/v1.28.1"
 * } ] }
 * ```
 * Each id is shown at most once per install.
 */
@Serializable
data class Advisory(
    val id: String = "",
    @SerialName("min_version_code") val minVersionCode: Int = 0,
    @SerialName("max_version_code") val maxVersionCode: Int = 0,
    val message: Map<String, String> = emptyMap(),
    val url: String? = null,
) {
    fun appliesTo(versionCode: Int): Boolean = versionCode in minVersionCode..maxVersionCode

    /** The message for [locale]: full tag, then language, then English, then any. */
    fun messageFor(locale: Locale): String? =
        message[locale.toLanguageTag()] ?: message[locale.language] ?: message["en"] ?: message.values.firstOrNull()

    /** [url] only when it is an https link; anything else is not offered. */
    val safeUrl: String?
        get() = url?.takeIf { it.startsWith("https://") }
}

@Serializable
data class AdvisoryFeed(val advisories: List<Advisory> = emptyList())

object UpdateAdvisories {
    private const val TAG = "UpdateAdvisories"
    const val FEED_URL = "https://raw.githubusercontent.com/Xslx98/LRReader/main/advisory.json"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** The feed's advisories; empty when it cannot be fetched or parsed (never blocks anything). */
    suspend fun fetch(client: OkHttpClient, url: String = FEED_URL): List<Advisory> =
        try {
            client.newCall(Request.Builder().url(url).build()).await().use { response ->
                val body = if (response.isSuccessful) response.body?.string() else null
                if (body == null) emptyList() else json.decodeFromString<AdvisoryFeed>(body).advisories
            }
        } catch (e: IOException) {
            unavailable(e)
        } catch (e: IllegalArgumentException) {
            // Malformed JSON (SerializationException is a subclass).
            unavailable(e)
        }

    private fun unavailable(e: Exception): List<Advisory> {
        Log.w(TAG, "Advisory feed unavailable", e)
        return emptyList()
    }

    /** The first advisory for [versionCode] not yet shown and with a message for [locale]. */
    fun pick(advisories: List<Advisory>, versionCode: Int, seen: Set<String>, locale: Locale): Advisory? =
        advisories.firstOrNull {
            it.id.isNotBlank() && it.id !in seen && it.appliesTo(versionCode) && it.messageFor(locale) != null
        }
}
