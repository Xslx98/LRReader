package com.hippo.ehviewer.client.lrr

import android.util.Log
import com.hippo.ehviewer.client.lrr.data.LRRArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API class for LANraragi archive operations.
 *
 * Endpoints:
 * - GET    /api/archives/:id/metadata    — Get metadata
 * - PUT    /api/archives/:id/metadata    — Update metadata (tags)
 * - GET    /api/archives/:id/files       — Extract archive, get page list
 * - GET    /api/archives/:id/page        — Get specific page image URL
 * - DELETE /api/archives/:id/isnew       — Clear "new" flag
 * - PUT    /api/archives/:id/progress/:p — Update reading progress
 * - DELETE /api/archives/:id             — Delete archive
 * - PUT    /api/archives/upload          — Upload archive file
 */
object LRRArchiveApi {

    private const val TAG = "LRRArchiveApi"

    /**
     * GET /api/archives/:id/metadata — Get archive metadata.
     */
    @JvmStatic
    suspend fun getArchiveMetadata(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String
    ): LRRArchive = retryOnFailure {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/api/archives/$arcid/metadata")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                ensureSuccess(response)
                val body = response.body?.string()
                    ?: throw LRREmptyBodyException()
                lrrJson.decodeFromString<LRRArchive>(body)
            }
        }
    }

    /**
     * PUT /api/archives/:id/metadata — Update archive tags.
     * Used for writing star ratings back as "rating:X" tags.
     */
    @JvmStatic
    suspend fun updateArchiveMetadata(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String,
        tags: String
    ) = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/archives/$arcid/metadata".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("tags", tags)
            .build()
        Log.d(TAG, "updateArchiveMetadata URL: $url")
        Log.d(TAG, "updateArchiveMetadata tags: $tags")
        val request = Request.Builder()
            .url(url)
            .put(EMPTY_REQUEST_BODY)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            Log.d(TAG, "updateArchiveMetadata response: ${response.code} $body")
            if (!response.isSuccessful) {
                throw LRRHttpException(response.code)
            }
        }
    }

    /**
     * GET /api/archives/:id/files — Extract archive and get page list.
     * This is the official endpoint that replaces the deprecated POST /extract.
     *
     * @return Array of page URLs (relative to server root).
     */
    @JvmStatic
    suspend fun getFileList(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String
    ): Array<String> = withContext(Dispatchers.IO) {
        // Use longer timeout for extraction (large archives can be slow)
        val longClient = client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/api/archives/$arcid/files")
            .get()
            .build()
        longClient.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            val jsonObj = Json.parseToJsonElement(body).jsonObject
            val pages = jsonObj["pages"]?.jsonArray
                ?: throw LRRMissingFieldException("pages")
            Array(pages.size) { i -> pages[i].jsonPrimitive.content }
        }
    }

    /**
     * DELETE /api/archives/:id — Delete an archive from the server.
     * This permanently removes both the metadata and the file.
     *
     * @return deleted filename on success.
     */
    @JvmStatic
    suspend fun deleteArchive(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/archives/$arcid")
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            val jsonObj = Json.parseToJsonElement(body).jsonObject
            val success = jsonObj["success"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (success != 1) {
                val error = jsonObj["error"]?.jsonPrimitive?.content ?: "Unknown error"
                throw IOException(error)
            }
            jsonObj["filename"]?.jsonPrimitive?.content ?: ""
        }
    }

    /**
     * PUT /api/archives/upload — Upload an archive file to the server.
     *
     * @param file the local archive file (ZIP/RAR/CBZ/etc.)
     * @param title optional title override
     * @param tags optional comma-separated tags
     * @param categoryId optional category to add the archive to
     * @return the arcid of the uploaded archive.
     */
    @JvmStatic
    suspend fun uploadArchive(
        client: OkHttpClient,
        baseUrl: String,
        file: File,
        title: String? = null,
        tags: String? = null,
        categoryId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val longClient = client.newBuilder()
            .writeTimeout(300, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("application/octet-stream".toMediaType())
            )
        if (!title.isNullOrEmpty()) bodyBuilder.addFormDataPart("title", title)
        if (!tags.isNullOrEmpty()) bodyBuilder.addFormDataPart("tags", tags)
        if (!categoryId.isNullOrEmpty()) bodyBuilder.addFormDataPart("category_id", categoryId)

        val request = Request.Builder()
            .url("$baseUrl/api/archives/upload")
            .put(bodyBuilder.build())
            .build()
        longClient.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            val jsonObj = Json.parseToJsonElement(body).jsonObject
            val success = jsonObj["success"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (success != 1) {
                val error = jsonObj["error"]?.jsonPrimitive?.content ?: "Upload failed"
                throw IOException(error)
            }
            jsonObj["id"]?.jsonPrimitive?.content ?: ""
        }
    }

    /**
     * Construct the URL for fetching a specific page image.
     *
     * @param pagePath Page path from extractArchive() result
     */
    @JvmStatic
    fun getPageUrl(baseUrl: String, arcid: String, pagePath: String): String {
        return "$baseUrl/api/archives/$arcid/page".toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("path", pagePath)
            .build()
            .toString()
    }

    /**
     * DELETE /api/archives/:id/isnew — Clear the "new" flag.
     */
    @JvmStatic
    suspend fun clearNewFlag(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/archives/$arcid/isnew")
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }

    /**
     * PUT /api/archives/:id/progress/:page — Update reading progress.
     */
    @JvmStatic
    suspend fun updateProgress(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String,
        page: Int
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/archives/$arcid/progress/$page")
            .put(EMPTY_REQUEST_BODY)
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }

    // ==================== Simplified overloads (use LRRClientProvider) ====================

    @JvmStatic
    suspend fun getArchiveMetadata(arcid: String): LRRArchive =
        getArchiveMetadata(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), arcid)

    @JvmStatic
    suspend fun updateArchiveMetadata(arcid: String, tags: String) =
        updateArchiveMetadata(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), arcid, tags)

    @JvmStatic
    suspend fun getFileList(arcid: String): Array<String> =
        getFileList(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), arcid)

    @JvmStatic
    fun getPageUrl(arcid: String, pagePath: String): String =
        getPageUrl(LRRClientProvider.getBaseUrl(), arcid, pagePath)

    @JvmStatic
    suspend fun clearNewFlag(arcid: String) =
        clearNewFlag(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), arcid)

    @JvmStatic
    suspend fun updateProgress(arcid: String, page: Int) =
        updateProgress(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), arcid, page)

    @JvmStatic
    suspend fun deleteArchive(arcid: String): String =
        deleteArchive(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), arcid)

    @JvmStatic
    suspend fun uploadArchive(file: File, title: String? = null, tags: String? = null, categoryId: String? = null): String =
        uploadArchive(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), file, title, tags, categoryId)
}
