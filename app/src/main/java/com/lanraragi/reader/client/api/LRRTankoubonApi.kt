package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.data.LRRArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * API class for LANraragi Tankoubon operations.
 *
 * Tankoubons are ORDERED collections of archives with their own metadata,
 * cover and a global reading progress. The tank's reading progress is a
 * global 1-indexed page spanning members in order (two members of 20+25
 * pages: global page 26 = member #2 page 6); the `page` params on the
 * list/full endpoints below are pagination indices, not reading pages.
 *
 * Endpoints (read):
 * - GET /api/tankoubons — list (paginated)
 * - GET /api/tankoubons/{id}/full — member archives with full metadata (0.9.8+)
 * - GET /api/tankoubons/{id}/thumbnail — cover (URL builder only)
 * - GET /api/archives/{id}/tankoubons — reverse lookup (which tanks contain an archive)
 *
 * Older servers answer 404 on the 0.9.8-only routes — callers treat
 * [LRRHttpException] 404 as "no tankoubon support" via TankoubonSupportGate.
 * GET /api/tankoubons/{id} is deliberately NOT wrapped: no caller, and its
 * pre-0.9.8 shape would decode into empty defaults instead of failing.
 *
 * Endpoints (write):
 * - PUT    /api/tankoubons                    🔑 — create (or rename via `id`)
 * - PUT    /api/tankoubons/{id}                🔑 — replace archives and/or metadata (JSON body)
 * - DELETE /api/tankoubons/{id}                🔑 — delete the tank (not its archives)
 * - PUT    /api/tankoubons/{id}/{archive}       🔑 — append an archive at the tank's end
 * - DELETE /api/tankoubons/{id}/{archive}       🔑 — remove an archive from the tank
 * - PUT    /api/tankoubons/{id}/thumbnail?page= 🔑 — set cover from a global 1-indexed page
 * - PUT    /api/tankoubons/{id}/progress/{page} 🔑 — set global 1-indexed reading progress
 *
 * [updateTankoubon] is this package's first JSON-body endpoint: the server
 * treats an ABSENT key as "leave untouched", so null params must not be
 * serialized at all (sending them as JSON `null` would be a request to wipe
 * that field).
 */
object LRRTankoubonApi {

    @Serializable
    data class Tankoubon(
        val id: String = "",
        val name: String = "",
        val archives: List<String> = emptyList(),
        val summary: String? = null,
        val tags: String? = null,
        val progress: Int = 0,
    )

    @Serializable
    data class TankoubonListResult(
        val result: List<Tankoubon> = emptyList(),
        val total: Int = 0,
        val filtered: Int = 0,
    )

    @Serializable
    data class TankoubonFull(
        val id: String = "",
        val name: String = "",
        val summary: String? = null,
        val tags: String? = null,
        val progress: Int = 0,
        /** Full ordered member id list (server order). */
        val archives: List<String> = emptyList(),
        @SerialName("full_data") val fullData: List<LRRArchive> = emptyList(),
    )

    @Serializable
    data class TankoubonFullResult(
        val result: TankoubonFull = TankoubonFull(),
        val total: Int = 0,
        val filtered: Int = 0,
    )

    @Serializable
    private data class ArchiveTankoubonsResult(val tankoubons: List<String> = emptyList())

    /**
     * GET /api/tankoubons — list all tankoubons (paginated by server page size).
     *
     * @param page the server's 0-BASED page index (unlike search's `start`
     *   offset). null or values <= 0 omit the query param — the server
     *   serves page 0; page=1 is the SECOND page, so a 1-based pagination
     *   loop reads an empty first fetch (real-server smoke 2026-07-05).
     */
    @JvmStatic
    suspend fun getTankoubons(
        client: OkHttpClient,
        baseUrl: String,
        page: Int? = null,
    ): TankoubonListResult = withContext(Dispatchers.IO) {
        val urlBuilder = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/tankoubons")
        if (page != null && page > 0) urlBuilder.addQueryParameter("page", page.toString())
        val request = Request.Builder().url(urlBuilder.build()).get().build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string() ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<TankoubonListResult>(body)
        }
    }

    /**
     * GET /api/tankoubons/{id}/full?page=-1 — member archives with full
     * metadata. page=-1 (default) returns ALL members in one response.
     */
    @JvmStatic
    suspend fun getTankoubonFull(
        client: OkHttpClient,
        baseUrl: String,
        tankId: String,
        page: Int = -1,
    ): TankoubonFullResult = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/tankoubons")
            .addPathSegment(requireValidTankId(tankId))
            .addPathSegment("full")
            .addQueryParameter("page", page.toString())
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string() ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<TankoubonFullResult>(body)
        }
    }

    /** GET /api/archives/{id}/tankoubons — ids of tanks containing [arcid]. */
    @JvmStatic
    suspend fun getArchiveTankoubons(
        client: OkHttpClient,
        baseUrl: String,
        arcid: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(requireValidArcid(arcid))
            .addPathSegment("tankoubons")
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string() ?: throw LRREmptyBodyException()
            lrrJson.decodeFromString<ArchiveTankoubonsResult>(body).tankoubons
        }
    }

    /**
     * Cover thumbnail URL for a tank. No `no_fallback`: a not-yet-generated
     * cover serves a server-side placeholder image, so list loaders need no
     * 202 handling. [cacheBust] (epoch ms) appends a throwaway query param so
     * a just-changed cover bypasses the image cache; 0 = no param.
     */
    @JvmStatic
    fun getTankoubonThumbnailUrl(baseUrl: String, tankId: String, cacheBust: Long = 0L): String {
        val b = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/tankoubons")
            .addPathSegment(requireValidTankId(tankId))
            .addPathSegment("thumbnail")
        if (cacheBust > 0L) b.addQueryParameter("ts", cacheBust.toString())
        return b.build().toString()
    }

    @Serializable
    private data class CreateTankoubonResult(
        @SerialName("tankoubon_id") val tankoubonId: String? = null,
        val success: Int = 0,
        val error: String? = null,
    )

    /** PUT /api/tankoubons 🔑 — create a new tankoubon, returns its id. */
    @JvmStatic
    suspend fun createTankoubon(client: OkHttpClient, baseUrl: String, name: String): String =
        createOrRename(client, baseUrl, name, existingId = null)

    /** PUT /api/tankoubons 🔑 — rename an existing tankoubon. */
    @JvmStatic
    suspend fun renameTankoubon(client: OkHttpClient, baseUrl: String, tankId: String, name: String) {
        createOrRename(client, baseUrl, name, existingId = requireValidTankId(tankId))
    }

    private suspend fun createOrRename(
        client: OkHttpClient,
        baseUrl: String,
        name: String,
        existingId: String?,
    ): String = withContext(Dispatchers.IO) {
        if (name.isBlank()) throw LRRClientValidationException("Tankoubon name must not be blank")
        val url = parseBaseUrl(baseUrl).newBuilder().addPathSegments("api/tankoubons").build()
        val form = FormBody.Builder().add("name", name)
        if (existingId != null) form.add("id", existingId)
        val request = Request.Builder().url(url).put(form.build()).build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            val body = response.body?.string() ?: throw LRREmptyBodyException()
            val parsed = lrrJson.decodeFromString<CreateTankoubonResult>(body)
            if (parsed.success != 1) throw IOException(parsed.error ?: "create_tankoubon failed")
            parsed.tankoubonId ?: throw LRRMissingFieldException("tankoubon_id")
        }
    }

    /**
     * PUT /api/tankoubons/{id} 🔑 — replace contents and/or metadata. JSON body
     * (the package's first): keys that are null are NOT sent — the server treats
     * an absent key as "leave untouched" (sending it empty would wipe data).
     *
     * @param archives `emptyList()` REPLACES the tank with zero members (wipes
     *   contents); pass `null` to leave membership untouched.
     */
    @JvmStatic
    @Suppress("LongParameterList")
    suspend fun updateTankoubon(
        client: OkHttpClient,
        baseUrl: String,
        tankId: String,
        archives: List<String>? = null,
        name: String? = null,
        summary: String? = null,
        tags: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            if (listOf(archives, name, summary, tags).all { it == null }) {
                throw LRRClientValidationException("updateTankoubon called with nothing to update")
            }
            val payload = buildJsonObject {
                if (archives != null) {
                    putJsonArray("archives") { archives.forEach { add(JsonPrimitive(requireValidArcid(it))) } }
                }
                if (name != null || summary != null || tags != null) {
                    putJsonObject("metadata") {
                        if (name != null) put("name", name)
                        if (summary != null) put("summary", summary)
                        if (tags != null) put("tags", tags)
                    }
                }
            }
            val url = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/tankoubons")
                .addPathSegment(requireValidTankId(tankId))
                .build()
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).put(body).build()
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
        }
    }

    /** DELETE /api/tankoubons/{id} 🔑 — removes the tank, never its archives. */
    @JvmStatic
    suspend fun deleteTankoubon(client: OkHttpClient, baseUrl: String, tankId: String) {
        withContext(Dispatchers.IO) {
            val url = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/tankoubons")
                .addPathSegment(requireValidTankId(tankId))
                .build()
            val request = Request.Builder().url(url).delete().build()
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
        }
    }

    /** PUT /api/tankoubons/{id}/{archive} 🔑 — append [arcid] at the tank's end. */
    @JvmStatic
    suspend fun addToTankoubon(client: OkHttpClient, baseUrl: String, tankId: String, arcid: String) {
        withContext(Dispatchers.IO) {
            val url = memberUrl(baseUrl, tankId, arcid)
            val request = Request.Builder().url(url).put(EMPTY_REQUEST_BODY).build()
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
        }
    }

    /** DELETE /api/tankoubons/{id}/{archive} 🔑 — remove [arcid] from the tank. */
    @JvmStatic
    suspend fun removeFromTankoubon(client: OkHttpClient, baseUrl: String, tankId: String, arcid: String) {
        withContext(Dispatchers.IO) {
            val url = memberUrl(baseUrl, tankId, arcid)
            val request = Request.Builder().url(url).delete().build()
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
        }
    }

    private fun memberUrl(baseUrl: String, tankId: String, arcid: String) =
        parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/tankoubons")
            .addPathSegment(requireValidTankId(tankId))
            .addPathSegment(requireValidArcid(arcid))
            .build()

    /** PUT /api/tankoubons/{id}/thumbnail?page= 🔑 — cover from a GLOBAL 1-indexed page. */
    @JvmStatic
    suspend fun updateTankThumbnail(client: OkHttpClient, baseUrl: String, tankId: String, globalPage1: Int) {
        withContext(Dispatchers.IO) {
            if (globalPage1 < 1) throw LRRClientValidationException("globalPage1 must be >= 1: $globalPage1")
            val url = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/tankoubons")
                .addPathSegment(requireValidTankId(tankId))
                .addPathSegment("thumbnail")
                .addQueryParameter("page", globalPage1.toString())
                .build()
            val request = Request.Builder().url(url).put(EMPTY_REQUEST_BODY).build()
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
        }
    }

    /**
     * PUT /api/tankoubons/{id}/progress/{page} — GLOBAL 1-indexed reading
     * progress. Skipped on clientside-progress servers (F5 pattern, mirrors
     * LRRArchiveApi.updateProgress).
     */
    @JvmStatic
    suspend fun updateTankProgress(client: OkHttpClient, baseUrl: String, tankId: String, globalPage1: Int) {
        withContext(Dispatchers.IO) {
            if (globalPage1 < 1) throw LRRClientValidationException("globalPage1 must be >= 1: $globalPage1")
            if (ServerCapabilityCache.tracksProgress(baseUrl) == false) return@withContext
            val url = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/tankoubons")
                .addPathSegment(requireValidTankId(tankId))
                .addPathSegment("progress")
                .addPathSegment(globalPage1.toString())
                .build()
            val request = Request.Builder().url(url).put(EMPTY_REQUEST_BODY).build()
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
        }
    }
}
