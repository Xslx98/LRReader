package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.data.LRRArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for LANraragi Tankoubon operations.
 *
 * Tankoubons are ORDERED collections of archives with their own metadata,
 * cover and a global reading progress whose page numbering spans all member
 * archives in order (two members of 20+25 pages: global page 26 = member #2
 * page 6). All page params here are global 1-INDEXED.
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

    /** GET /api/tankoubons — list all tankoubons (paginated by server page size). */
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
}
