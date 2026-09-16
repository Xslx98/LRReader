package com.lanraragi.reader.dao

import com.lanraragi.reader.client.api.data.LRRCategory

/**
 * Repository for quick-search-related database operations, backed by [BrowsingRoomDao].
 *
 * Thin delegation layer extracted from [com.lanraragi.reader.EhDB] as part of the
 * incremental God Object decomposition. No business logic beyond what EhDB
 * already had (id/time assignment, reorder algorithm).
 *
 * Registered as a lazy val in [com.lanraragi.reader.module.DataModule].
 */
class QuickSearchRepository(private val dao: BrowsingRoomDao) {

    suspend fun getAll(): List<QuickSearch> =
        dao.getAllQuickSearch()

    suspend fun insert(quickSearch: QuickSearch) {
        quickSearch.id = null
        if (quickSearch.time == 0L) {
            quickSearch.time = System.currentTimeMillis()
        }
        quickSearch.id = dao.insertQuickSearch(quickSearch)
    }

    suspend fun update(quickSearch: QuickSearch) {
        dao.updateQuickSearch(quickSearch)
    }

    suspend fun delete(quickSearch: QuickSearch) {
        dao.deleteQuickSearch(quickSearch)
    }

    /**
     * Keep CATEGORY_NAME (display-only) in step with the server after a
     * successful categories fetch: fills the names MIGRATION_30_31 left NULL
     * and follows server-side renames. Idempotent — each UPDATE is guarded on
     * "missing or different", so a repeat call changes nothing. Categories
     * without a usable id or name are skipped. Returns rows changed.
     */
    suspend fun syncCategoryNames(categories: List<LRRCategory>): Int {
        var changed = 0
        for (category in categories) {
            val id = category.id?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val name = category.name?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            changed += dao.updateQuickSearchCategoryName(id, name)
        }
        return changed
    }

    suspend fun move(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val reverse = fromPosition > toPosition
        val offset = if (reverse) toPosition else fromPosition
        val limit = if (reverse) fromPosition - toPosition + 1 else toPosition - fromPosition + 1
        val list = dao.getQuickSearchRange(offset, limit)
        val step = if (reverse) 1 else -1
        val start = if (reverse) limit - 1 else 0
        val end = if (reverse) 0 else limit - 1
        val toTime = list[end].time
        var i = end
        while (if (reverse) i < start else i > start) {
            list[i].time = list[i + step].time
            i += step
        }
        list[start].time = toTime
        dao.updateQuickSearchList(list)
    }
}
