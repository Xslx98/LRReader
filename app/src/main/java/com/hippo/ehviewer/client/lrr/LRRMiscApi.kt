package com.hippo.ehviewer.client.lrr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * API class for miscellaneous LANraragi operations.
 *
 * Endpoints:
 * - POST /api/download_url — Queue a URL download on the server
 */
object LRRMiscApi {

    /**
     * POST /api/download_url — Queue a URL download.
     * The server will download the file at the given URL and add it to the library.
     *
     * @param url the URL to download
     * @param catid optional category ID to add the archive to
     * @return the minion job ID
     */
    @JvmStatic
    suspend fun downloadUrl(
        client: OkHttpClient,
        baseUrl: String,
        url: String,
        catid: String? = null
    ): Int = withContext(Dispatchers.IO) {
        val urlBuilder = "$baseUrl/api/download_url".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("url", url)
        if (!catid.isNullOrEmpty()) urlBuilder.addQueryParameter("catid", catid)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .post(EMPTY_REQUEST_BODY)
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            val jsonObj = Json.parseToJsonElement(body).jsonObject
            val success = jsonObj["success"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (success != 1) {
                val error = jsonObj["error"]?.jsonPrimitive?.content ?: "Download queue failed"
                throw IOException(error)
            }
            jsonObj["job"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        }
    }

    // ==================== Simplified overload ====================

    @JvmStatic
    suspend fun downloadUrl(url: String, catid: String? = null): Int =
        downloadUrl(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), url, catid)
}
