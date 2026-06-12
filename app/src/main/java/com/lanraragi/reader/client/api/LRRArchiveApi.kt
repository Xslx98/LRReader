package com.lanraragi.reader.client.api

import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.data.LRRArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * API class for LANraragi archive operations.
 *
 * Endpoints:
 * - GET    /api/archives/:id/metadata          — Get metadata
 * - PUT    /api/archives/:id/metadata          — Update metadata (tags)
 * - GET    /api/archives/:id/files             — Extract archive, get page list
 * - GET    /api/archives/:id/page              — Get specific page image URL
 * - GET    /api/archives/:id/thumbnail?page=N  — Get per-page thumbnail (200 jpeg / 202 job)
 * - DELETE /api/archives/:id/isnew             — Clear "new" flag
 * - PUT    /api/archives/:id/progress/:p       — Update reading progress
 * - DELETE /api/archives/:id                   — Delete archive
 * - PUT    /api/archives/upload                — Upload archive file
 */
object LRRArchiveApi {

    /**
     * Result of a per-page thumbnail fetch. LANraragi answers
     * `GET /api/archives/:id/thumbnail?page=N` with two distinct
     * 2xx states that the caller must dispatch differently:
     *  - **200 + image/jpeg**: thumbnail is already on disk → [Ready].
     *  - **202 + application/json `{job: <id>}`**: a Minion worker is
     *    generating the thumbnail asynchronously → [Pending].
     */
    sealed class PageThumbnailFetchResult {
        /** Server returned the image bytes. */
        class Ready(val bytes: ByteArray) : PageThumbnailFetchResult()

        /**
         * Server queued the thumbnail for async generation. [jobId] is
         * the Minion job id if the response body parsed cleanly, or
         * null when the JSON envelope was unparsable (in which case the
         * caller should still treat the request as pending and retry).
         */
        data class Pending(val jobId: Long?) : PageThumbnailFetchResult()
    }

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
            val url = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/archives")
                .addPathSegment(requireValidArcid(arcid))
                .addPathSegment("metadata")
                .build()
            val request = Request.Builder()
                .url(url)
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
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("metadata")
            .addQueryParameter("tags", tags)
            .build()
        val request = Request.Builder()
            .url(url)
            .put(EMPTY_REQUEST_BODY)
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }

    /**
     * PUT /api/archives/:id/metadata — Update archive title, tags, and/or summary.
     *
     * Per the OpenAPI spec, this endpoint takes its inputs as **query parameters**
     * (the spec declares `parameters: [title, tags, summary]` and no requestBody).
     * The previous form-body implementation worked against historical Mojolicious
     * leniency but is rejected by spec-strict server builds.
     *
     * @param title   new title, or null to leave unchanged
     * @param tags    new comma-separated tags, or null to leave unchanged
     * @param summary new summary, or null to leave unchanged
     */
    @JvmStatic
    suspend fun updateMetadata(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String,
        title: String? = null,
        tags: String? = null,
        summary: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        val urlBuilder = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("metadata")
        if (title != null) urlBuilder.addQueryParameter("title", title)
        if (tags != null) urlBuilder.addQueryParameter("tags", tags)
        if (summary != null) urlBuilder.addQueryParameter("summary", summary)
        val request = Request.Builder()
            .url(urlBuilder.build())
            .put(EMPTY_REQUEST_BODY)
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
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
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("files")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
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
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .build()
        val request = Request.Builder()
            .url(url)
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
     * @param summary optional archive summary
     * @param fileChecksum optional SHA-1 (40 hex chars) of [file] for the
     *   server's in-transit integrity check; a mismatch is rejected with HTTP 417
     * @return the arcid of the uploaded archive.
     */
    // The parameter count mirrors the upload endpoint's flat multipart contract
    // (file + optional title/tags/category/summary/checksum), not poor design.
    @JvmStatic
    @Suppress("LongParameterList")
    suspend fun uploadArchive(
        client: OkHttpClient,
        baseUrl: String,
        file: File,
        title: String? = null,
        tags: String? = null,
        categoryId: String? = null,
        summary: String? = null,
        fileChecksum: String? = null,
        progressListener: ((written: Long, total: Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val fileBody = file.asRequestBody("application/octet-stream".toMediaType())
        val filePart = if (progressListener != null) {
            ProgressRequestBody(fileBody, progressListener)
        } else {
            fileBody
        }
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, filePart)
        if (!title.isNullOrEmpty()) bodyBuilder.addFormDataPart("title", title)
        if (!tags.isNullOrEmpty()) bodyBuilder.addFormDataPart("tags", tags)
        if (!categoryId.isNullOrEmpty()) bodyBuilder.addFormDataPart("category_id", categoryId)
        if (!summary.isNullOrEmpty()) bodyBuilder.addFormDataPart("summary", summary)
        if (!fileChecksum.isNullOrEmpty()) bodyBuilder.addFormDataPart("file_checksum", fileChecksum)

        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives/upload")
            .build()
        val request = Request.Builder()
            .url(url)
            .put(bodyBuilder.build())
            .build()
        client.newCall(request).execute().use { response ->
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
     * A [RequestBody] decorator that reports upload progress as bytes are
     * written to the network. [listener] receives the running byte count and
     * the part's total size on each chunk, ending at (total, total). Used to
     * drive a determinate upload progress bar.
     */
    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val listener: (written: Long, total: Long) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            val countingSink = object : ForwardingSink(sink) {
                private var written = 0L
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    listener(written, total)
                }
            }
            val buffered = countingSink.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }

    /**
     * Probe whether [arcid] already exists on the server, for a pre-upload
     * duplicate check. Returns true when `GET /metadata` succeeds (200), false
     * when the server reports the id is unknown (HTTP 400 — LANraragi's answer
     * for a non-existent arcid — or 404). Any other failure (network, 5xx,
     * reverse-proxy) propagates so the caller can decide; the upload path treats
     * an inconclusive probe as "not a confirmed duplicate" and proceeds.
     */
    @JvmStatic
    suspend fun archiveExists(client: OkHttpClient, baseUrl: String, arcid: String): Boolean =
        try {
            getArchiveMetadata(client, baseUrl, arcid)
            true
        } catch (e: LRRHttpException) {
            if (e.code == HTTP_BAD_REQUEST || e.code == HTTP_NOT_FOUND) false else throw e
        }

    /**
     * Compute the LANraragi archive id (arcid) for [input] locally: the SHA-1
     * (40 lowercase hex chars) of the **first [ARCHIVE_ID_HASH_BYTES] bytes**
     * of the stream, mirroring the server's `compute_id`. Reads at most that
     * many bytes, so a large archive can be fingerprinted from its Uri without
     * a full copy — enabling a cheap pre-upload duplicate check.
     *
     * The caller owns [input] and is responsible for closing it.
     */
    @JvmStatic
    fun computeArchiveId(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(8192)
        var remaining = ARCHIVE_ID_HASH_BYTES
        while (remaining > 0) {
            val toRead = minOf(buffer.size, remaining)
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            digest.update(buffer, 0, read)
            remaining -= read
        }
        return digest.digest().toHexLower()
    }

    /**
     * Compute the SHA-1 checksum (40 lowercase hex chars) of [file], in the
     * format LANraragi's upload endpoint expects for the optional
     * `file_checksum` field used for in-transit integrity validation.
     */
    @JvmStatic
    fun computeFileChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexLower()
    }

    /**
     * Construct the URL for fetching a specific page image.
     *
     * @param pagePath Page path from extractArchive() result
     */
    @JvmStatic
    fun getPageUrl(baseUrl: String, arcid: String, pagePath: String): String {
        return parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("page")
            .addQueryParameter("path", pagePath)
            .build()
            .toString()
    }

    /**
     * Construct the URL for a per-page thumbnail.
     *
     * `no_fallback=true` tells LANraragi to return 202 + a job
     * descriptor rather than substituting the cover thumbnail while
     * the page-specific thumbnail is being generated. Without this
     * we cannot tell "thumb ready" from "cover stand-in" and the
     * preview grid would render the same cover for every page until
     * the user reopens the detail page.
     *
     * @param page 0-indexed page number. Converted to the server's 1-indexed
     *   page on the wire (`page + 1`): LANraragi stores per-page thumbnails as
     *   1.jpg..N.jpg and treats `page=0` as the cover, so sending the raw
     *   0-indexed value shows every tile one page off (and never the last page).
     */
    @JvmStatic
    fun getPageThumbnailUrl(baseUrl: String, arcid: String, page: Int): String {
        return parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("thumbnail")
            .addQueryParameter("page", (page + 1).toString())
            .addQueryParameter("no_fallback", "true")
            .build()
            .toString()
    }

    /**
     * GET /api/archives/:id/thumbnail?page=N — Fetch a single page thumbnail.
     *
     * Returns [PageThumbnailFetchResult.Ready] when the server has the
     * thumbnail on disk (HTTP 200 + JPEG bytes), or
     * [PageThumbnailFetchResult.Pending] when generation is in progress
     * (HTTP 202 + JSON job descriptor). The caller is responsible for
     * polling on Pending (typically with backoff) until either a Ready
     * lands or a retry budget is exhausted.
     *
     * Unlike most archive endpoints this one is **not** wrapped in
     * [retryOnFailure]: the per-page thumbnail loader runs its own
     * 202-aware backoff loop and a second outer retry layer would
     * double the attempt budget without adding signal.
     *
     * @param page 0-indexed page number. Sent to the server as `page + 1`
     *   (LANraragi per-page thumbnails are 1-indexed; `page=0` is the cover).
     */
    @JvmStatic
    suspend fun fetchPageThumbnail(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String,
        page: Int
    ): PageThumbnailFetchResult = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("thumbnail")
            .addQueryParameter("page", (page + 1).toString())
            .addQueryParameter("no_fallback", "true")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                HTTP_OK -> {
                    val bytes = response.body?.bytes()
                        ?: throw LRREmptyBodyException()
                    PageThumbnailFetchResult.Ready(bytes)
                }
                HTTP_ACCEPTED -> {
                    val body = response.body?.string().orEmpty()
                    val jobId = runCatching {
                        lrrJson.parseToJsonElement(body)
                            .jsonObject["job"]
                            ?.jsonPrimitive
                            ?.long
                    }.getOrNull()
                    PageThumbnailFetchResult.Pending(jobId)
                }
                else -> throw LRRHttpException(response.code)
            }
        }
    }

    private const val HTTP_OK = 200
    private const val HTTP_ACCEPTED = 202
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_NOT_FOUND = 404

    /**
     * Number of leading file bytes LANraragi's `compute_id` hashes to derive an
     * archive id (the first 512 KB). Must match the server constant exactly: if
     * it drifts, [computeArchiveId] simply stops matching and the pre-upload
     * dedup check degrades to a no-op (the server's own check still catches
     * duplicates), so the failure mode is safe rather than wrong.
     */
    private const val ARCHIVE_ID_HASH_BYTES = 512_000

    /**
     * DELETE /api/archives/:id/isnew — Clear the "new" flag.
     */
    @JvmStatic
    suspend fun clearNewFlag(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String
    ) = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("isnew")
            .build()
        val request = Request.Builder()
            .url(url)
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
        // Spec: check /api/info before calling — on clientside-progress servers
        // this endpoint 400s. Skip the doomed call when a prior /api/info said so.
        if (ServerCapabilityCache.tracksProgress(baseUrl) == false) return@withContext
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("progress")
            .addPathSegment(page.toString())
            .build()
        val request = Request.Builder()
            .url(url)
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
    suspend fun archiveExists(arcid: String): Boolean =
        archiveExists(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), arcid)

    @JvmStatic
    suspend fun updateArchiveMetadata(arcid: String, tags: String) =
        updateArchiveMetadata(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), arcid, tags)

    @JvmStatic
    suspend fun updateMetadata(
        arcid: String,
        title: String? = null,
        tags: String? = null,
        summary: String? = null
    ) = updateMetadata(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), arcid, title, tags, summary)

    @JvmStatic
    suspend fun getFileList(arcid: String): Array<String> =
        getFileList(ServiceRegistry.networkModule.longReadClient, LRRClientProvider.getBaseUrl(), arcid)

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
    @Suppress("LongParameterList")
    suspend fun uploadArchive(
        file: File,
        title: String? = null,
        tags: String? = null,
        categoryId: String? = null,
        summary: String? = null,
        fileChecksum: String? = null,
        progressListener: ((written: Long, total: Long) -> Unit)? = null
    ): String =
        uploadArchive(
            ServiceRegistry.networkModule.uploadClient, LRRClientProvider.getBaseUrl(),
            file, title, tags, categoryId, summary, fileChecksum, progressListener
        )
}
