package com.lanraragi.reader.category

import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.ServerConnections

/** A static (hand-filled, not search-based) category as the membership dialog lists it. */
data class StaticCategory(val id: String?, val name: String?, val archiveIds: List<String>)

/**
 * Category membership of ONE archive (the detail page's category dialog
 * and the batch "add to category" pick). Categories live per server and
 * arcids are content hashes, so every call goes to the archive's SOURCE
 * profile, never simply the active server.
 */
class CategoryMembershipService(
    private val connectionFor: suspend (profileId: Long) -> ServerConnection = { ServerConnections.forProfile(it) },
) {

    /** The server that owns [serverProfileId]'s data and its static categories, in server order. */
    class Loaded(val conn: ServerConnection, val categories: List<StaticCategory>)

    suspend fun loadStatic(serverProfileId: Long): Loaded {
        val conn = connectionFor(serverProfileId)
        val categories = LRRCategoryApi.getCategories(conn.client, conn.baseUrl)
            .filter { it.search.isNullOrEmpty() }
            .map { StaticCategory(it.id, it.name, it.archives) }
        return Loaded(conn, categories)
    }

    /**
     * Adds or removes [arcid] for every category whose [checked] flag differs
     * from [original] (index-aligned with [categories]), one call at a time,
     * stopping at the first failure. Returns the names of the categories the
     * archive is now in, in list order.
     */
    suspend fun apply(
        conn: ServerConnection,
        arcid: String,
        categories: List<StaticCategory>,
        original: List<Boolean>,
        checked: List<Boolean>,
    ): List<String> {
        for (i in categories.indices) {
            if (checked[i] == original[i]) continue
            val catId = requireNotNull(categories[i].id) { "static category without an id" }
            if (checked[i]) {
                LRRCategoryApi.addToCategory(conn.client, conn.baseUrl, catId, arcid)
            } else {
                LRRCategoryApi.removeFromCategory(conn.client, conn.baseUrl, catId, arcid)
            }
        }
        return categories.indices.filter { checked[it] }.map { categories[it].name.orEmpty() }
    }

    /** Creates a static category named [name] and puts [arcid] in it; returns the new id. */
    suspend fun createWith(conn: ServerConnection, name: String, arcid: String): String {
        val newId = LRRCategoryApi.createCategory(conn.client, conn.baseUrl, name, null, false)
        LRRCategoryApi.addToCategory(conn.client, conn.baseUrl, newId, arcid)
        return newId
    }

    companion object {
        val default: CategoryMembershipService by lazy { CategoryMembershipService() }
    }
}
