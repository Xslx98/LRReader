package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.data.LRRCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * API class for LANraragi category operations.
 *
 * Endpoints:
 * - GET    /api/categories              — List all categories
 * - GET    /api/archives/:id/categories — Static categories containing one archive/tank
 * - PUT    /api/categories              — Create a new category
 * - PUT    /api/categories/:id          — Update a category
 * - DELETE /api/categories/:id          — Delete a category
 * - PUT    /api/categories/:id/:archive — Add archive to category
 * - DELETE /api/categories/:id/:archive — Remove archive from category
 */
object LRRCategoryApi {

    /**
     * GET /api/categories — Get all categories.
     */
    @JvmStatic
    suspend fun getCategories(
        client: OkHttpClient,
        baseUrl: String
    ): List<LRRCategory> = retryOnFailure {
        withContext(Dispatchers.IO) {
            val url = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegment("api")
                .addPathSegment("categories")
                .build()
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            client.newCall(request).await().use { response ->
                ensureSuccess(response)
                decodeJsonBody<List<LRRCategory>>(response)
            }
        }
    }

    @Serializable
    private class ArchiveCategoriesResult(val categories: List<LRRCategory> = emptyList())

    /**
     * GET /api/archives/{id}/categories — the static categories containing
     * [id] (an arcid, or a `TANK_` id on 0.9.81+; older servers answer 400
     * for a tank id). Every server since 0.9.0 has the endpoint, so an
     * archive's favourite heart never needs the full category list (audit C16).
     */
    @JvmStatic
    suspend fun getArchiveCategories(
        client: OkHttpClient,
        baseUrl: String,
        id: String
    ): List<LRRCategory> = retryOnFailure {
        withContext(Dispatchers.IO) {
            val url = parseBaseUrl(baseUrl).newBuilder()
                .addPathSegments("api/archives")
                .addPathSegment(requireValidCategoryMemberId(id))
                .addPathSegment("categories")
                .build()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).await().use { response ->
                ensureSuccess(response)
                decodeJsonBody<ArchiveCategoriesResult>(response).categories
            }
        }
    }

    /**
     * Static categories containing [id]. A `TANK_` id rejected with 400
     * (servers before 0.9.81 validate the path as a 40-char arcid) falls
     * back to filtering the full category list.
     */
    @JvmStatic
    suspend fun categoriesContaining(
        client: OkHttpClient,
        baseUrl: String,
        id: String
    ): List<LRRCategory> {
        val direct = try {
            getArchiveCategories(client, baseUrl, id)
        } catch (e: LRRHttpException) {
            if (e.code != HTTP_BAD_REQUEST || !isTankoubonId(id)) throw e
            null
        }
        if (direct != null) return direct
        return getCategories(client, baseUrl).filter { !it.isDynamic() && id in it.archives }
    }

    private const val HTTP_BAD_REQUEST = 400

    /**
     * PUT /api/categories — Create a new category.
     * Uses application/x-www-form-urlencoded request body per OpenAPI spec.
     * @return the ID of the created category.
     */
    @JvmStatic
    suspend fun createCategory(
        client: OkHttpClient,
        baseUrl: String,
        name: String,
        search: String? = null,
        pinned: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("name", name)
        if (!search.isNullOrEmpty()) {
            formBuilder.add("search", search)
        }
        if (pinned) {
            formBuilder.add("pinned", "true")
        }
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("categories")
            .build()
        val request = Request.Builder()
            .url(url)
            .put(formBuilder.build())
            .build()
        client.newCall(request).await().use { response ->
            ensureSuccess(response)
            val body = response.body?.string()
                ?: throw LRREmptyBodyException()
            val obj = lrrJson.parseToJsonElement(body).jsonObject
            obj["category_id"]?.jsonPrimitive?.content
                ?: throw LRRMissingFieldException("category_id")
        }
    }

    /**
     * PUT /api/categories/:id — Update a category.
     * Uses application/x-www-form-urlencoded request body per OpenAPI spec.
     */
    @JvmStatic
    suspend fun updateCategory(
        client: OkHttpClient,
        baseUrl: String,
        categoryId: String,
        name: String,
        search: String? = null,
        pinned: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("name", name)
        if (!search.isNullOrEmpty()) {
            formBuilder.add("search", search)
        }
        if (pinned) {
            formBuilder.add("pinned", "true")
        }
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("categories")
            .addPathSegment(requireValidCategoryId(categoryId))
            .build()
        val request = Request.Builder()
            .url(url)
            .put(formBuilder.build())
            .build()
        client.newCall(request).await().use { response ->
            ensureSuccess(response)
        }
    }

    /**
     * DELETE /api/categories/:id — Delete a category.
     */
    @JvmStatic
    suspend fun deleteCategory(
        client: OkHttpClient,
        baseUrl: String,
        categoryId: String
    ) = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("categories")
            .addPathSegment(requireValidCategoryId(categoryId))
            .build()
        val request = Request.Builder()
            .url(url)
            .delete()
            .build()
        client.newCall(request).await().use { response ->
            ensureSuccess(response)
        }
    }

    /**
     * PUT /api/categories/:id/:archive — Add archive to category.
     */
    @JvmStatic
    suspend fun addToCategory(
        client: OkHttpClient,
        baseUrl: String,
        categoryId: String,
        arcid: String
    ) = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("categories")
            .addPathSegment(requireValidCategoryId(categoryId))
            .addPathSegment(requireValidCategoryMemberId(arcid))
            .build()
        val request = Request.Builder()
            .url(url)
            .put(EMPTY_REQUEST_BODY)
            .build()
        client.newCall(request).await().use { response ->
            ensureSuccess(response)
        }
    }

    /**
     * DELETE /api/categories/:id/:archive — Remove archive from category.
     */
    @JvmStatic
    suspend fun removeFromCategory(
        client: OkHttpClient,
        baseUrl: String,
        categoryId: String,
        arcid: String
    ) = withContext(Dispatchers.IO) {
        val url = parseBaseUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("categories")
            .addPathSegment(requireValidCategoryId(categoryId))
            .addPathSegment(requireValidCategoryMemberId(arcid))
            .build()
        val request = Request.Builder()
            .url(url)
            .delete()
            .build()
        client.newCall(request).await().use { response ->
            ensureSuccess(response)
        }
    }

    // ==================== Simplified overloads (use LRRClientProvider) ====================

    @JvmStatic
    suspend fun getCategories(): List<LRRCategory> =
        getCategories(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl())

    @JvmStatic
    suspend fun createCategory(name: String, search: String? = null, pinned: Boolean = false): String =
        createCategory(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), name, search, pinned)

    @JvmStatic
    suspend fun updateCategory(categoryId: String, name: String, search: String? = null, pinned: Boolean = false) =
        updateCategory(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), categoryId, name, search, pinned)

    @JvmStatic
    suspend fun deleteCategory(categoryId: String) =
        deleteCategory(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), categoryId)

    @JvmStatic
    suspend fun addToCategory(categoryId: String, arcid: String) =
        addToCategory(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), categoryId, arcid)

    @JvmStatic
    suspend fun removeFromCategory(categoryId: String, arcid: String) =
        removeFromCategory(LRRClientProvider.getClient(), LRRClientProvider.getBaseUrl(), categoryId, arcid)
}
