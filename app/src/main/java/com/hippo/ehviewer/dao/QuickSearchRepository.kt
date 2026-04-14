package com.hippo.ehviewer.dao

/**
 * Repository for quick-search-related database operations, backed by [BrowsingRoomDao].
 *
 * Thin delegation layer extracted from [com.hippo.ehviewer.EhDB] as part of the
 * incremental God Object decomposition. No business logic beyond what EhDB
 * already had (id/time assignment, reorder algorithm).
 *
 * Registered as a lazy val in [com.hippo.ehviewer.module.DataModule].
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
