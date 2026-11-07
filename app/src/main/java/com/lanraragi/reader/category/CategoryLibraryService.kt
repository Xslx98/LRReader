package com.lanraragi.reader.category

import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.data.LRRCategory
import com.lanraragi.reader.domain.Category
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.ServerConnections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * The active server's category list as the categories screen manages it:
 * load (named categories, pinned first) and create / edit / delete.
 *
 * The writes run on the process scope, so a create, edit or delete the user
 * confirmed reaches the server even when the screen that asked for it closes
 * first; the caller only awaits the outcome, and cancelling that wait never
 * cancels the write. A failed write is logged here (its caller may be gone)
 * and rethrown to a caller that is still waiting.
 *
 * @param connection the server to talk to; null = no server configured
 * @param writeScope the long-lived scope writes run on; null = the app's
 *   application scope, resolved at the first write
 */
class CategoryLibraryService(
    private val connection: () -> ServerConnection? = { ServerConnections.active() },
    private val writeScope: CoroutineScope? = null,
) {

    /** Named categories of the active server, pinned first; null when no server is configured. */
    suspend fun load(): List<Category>? {
        val conn = connection() ?: return null
        return pinnedFirst(LRRCategoryApi.getCategories(conn.client, conn.baseUrl).map { it.toCategory() })
    }

    /** Creates a category; false when no server is configured (nothing was sent). */
    suspend fun create(name: String, search: String?, pinned: Boolean): Boolean = write { conn ->
        LRRCategoryApi.createCategory(conn.client, conn.baseUrl, name, search, pinned)
    }

    /** Updates a category; false when no server is configured (nothing was sent). */
    suspend fun update(categoryId: String, name: String, search: String?, pinned: Boolean): Boolean =
        write { conn -> LRRCategoryApi.updateCategory(conn.client, conn.baseUrl, categoryId, name, search, pinned) }

    /** Deletes a category; false when no server is configured (nothing was sent). */
    suspend fun delete(categoryId: String): Boolean = write { conn ->
        LRRCategoryApi.deleteCategory(conn.client, conn.baseUrl, categoryId)
    }

    private suspend fun write(block: suspend (ServerConnection) -> Unit): Boolean {
        val conn = connection() ?: return false
        val scope = writeScope ?: ServiceRegistry.coroutineModule.applicationScope
        scope.async {
            try {
                block(conn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Category write failed", e)
                throw e
            }
        }.await()
        return true
    }

    companion object {
        private const val TAG = "CategoryLibraryService"

        val default: CategoryLibraryService by lazy { CategoryLibraryService() }

        /** Drops nameless entries and puts pinned categories first, keeping server order within each group. */
        fun pinnedFirst(categories: List<Category>): List<Category> {
            val named = categories.filter { !it.name.isNullOrEmpty() }
            return named.filter { it.isPinned() } + named.filterNot { it.isPinned() }
        }
    }
}

private fun LRRCategory.toCategory(): Category =
    Category(
        id = id,
        name = name,
        archives = archives,
        search = search,
        pinned = isPinned(),
    )
