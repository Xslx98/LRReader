package com.hippo.ehviewer.dao

import androidx.room.withTransaction

/**
 * Repository for the per-profile automatic search history, backed by
 * [BrowsingRoomDao].
 *
 * Semantics fixed at triage (issue #6/#12):
 * - record only non-blank queries for a real profile, at dispatch time;
 * - exact-match re-record promotes the entry to the top (REPLACE refreshes
 *   LAST_USED) without duplicating;
 * - at most [MAX_ENTRIES] rows per profile — the upsert+evict pair runs in one
 *   transaction so a crash can't leave the cap exceeded;
 * - deleting a profile cascades via [deleteAllForProfile].
 *
 * Registered as a lazy val in [com.hippo.ehviewer.module.DataModule].
 *
 * @param now injectable clock (epoch millis) so recency ordering is
 *   deterministic under test.
 */
class SearchHistoryRepository(
    private val dao: BrowsingRoomDao,
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun recordSearch(profileId: Long, rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty() || profileId <= 0) return
        db.withTransaction {
            dao.upsertSearchHistory(SearchHistoryEntry(query, profileId, now()))
            dao.evictSearchHistoryBeyond(profileId, MAX_ENTRIES)
        }
    }

    suspend fun recentSearches(profileId: Long, limit: Int = RECENT_DISPLAY_LIMIT): List<String> =
        dao.getRecentSearchHistory(profileId, limit).map { it.query }

    suspend fun matchingSearches(
        profileId: Long,
        prefix: String,
        limit: Int = RECENT_DISPLAY_LIMIT
    ): List<String> {
        if (prefix.isBlank()) return recentSearches(profileId, limit)
        return dao.getMatchingSearchHistory(profileId, escapeLike(prefix), limit).map { it.query }
    }

    suspend fun deleteEntry(profileId: Long, query: String) =
        dao.deleteSearchHistoryEntry(profileId, query)

    suspend fun clearAll(profileId: Long) = dao.clearSearchHistory(profileId)

    /** Profile-deletion cascade hook — same operation, kept for call-site intent. */
    suspend fun deleteAllForProfile(profileId: Long) = clearAll(profileId)

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        const val MAX_ENTRIES = 50
        const val RECENT_DISPLAY_LIMIT = 10
    }
}
