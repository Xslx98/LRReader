package com.hippo.ehviewer.dao

import androidx.room.*

/**
 * Room DAO for the residual browsing-related tables: QUICK_SEARCH and
 * SEARCH_HISTORY.
 *
 * The HISTORY and LOCAL_FAVORITES tables are gone post-L1; their
 * subsystems live on `ARCHIVE_LOCAL_STATE` and are accessed via
 * [ArchiveLocalStateDao]. QUICK_SEARCH and SEARCH_HISTORY are unrelated
 * to per-archive state and stay on their own tables.
 */
@Dao
interface BrowsingRoomDao {

    @Query("SELECT * FROM QUICK_SEARCH ORDER BY TIME ASC")
    suspend fun getAllQuickSearch(): List<QuickSearch>

    @Insert
    suspend fun insertQuickSearch(search: QuickSearch): Long

    @Update
    suspend fun updateQuickSearch(search: QuickSearch)

    @Update
    suspend fun updateQuickSearchList(list: List<QuickSearch>)

    @Delete
    suspend fun deleteQuickSearch(search: QuickSearch)

    @Query("SELECT * FROM QUICK_SEARCH ORDER BY TIME ASC LIMIT :limit OFFSET :offset")
    suspend fun getQuickSearchRange(offset: Int, limit: Int): List<QuickSearch>

    // ---- SEARCH_HISTORY (per-profile automatic search history) ----

    /** Composite PK (QUERY, SERVER_PROFILE_ID) makes REPLACE = dedupe-promote. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchHistory(entry: SearchHistoryEntry)

    @Query(
        "SELECT * FROM SEARCH_HISTORY WHERE SERVER_PROFILE_ID = :profileId " +
            "ORDER BY LAST_USED DESC LIMIT :limit"
    )
    suspend fun getRecentSearchHistory(profileId: Long, limit: Int): List<SearchHistoryEntry>

    @Query(
        "SELECT * FROM SEARCH_HISTORY WHERE SERVER_PROFILE_ID = :profileId " +
            "AND QUERY LIKE :prefix || '%' ESCAPE '\\' ORDER BY LAST_USED DESC LIMIT :limit"
    )
    suspend fun getMatchingSearchHistory(
        profileId: Long,
        prefix: String,
        limit: Int
    ): List<SearchHistoryEntry>

    /** Keep the [keep] most-recent rows for the profile, delete the rest. */
    @Query(
        "DELETE FROM SEARCH_HISTORY WHERE SERVER_PROFILE_ID = :profileId AND QUERY IN (" +
            "SELECT QUERY FROM SEARCH_HISTORY WHERE SERVER_PROFILE_ID = :profileId " +
            "ORDER BY LAST_USED DESC LIMIT -1 OFFSET :keep)"
    )
    suspend fun evictSearchHistoryBeyond(profileId: Long, keep: Int)

    @Query("DELETE FROM SEARCH_HISTORY WHERE SERVER_PROFILE_ID = :profileId AND QUERY = :query")
    suspend fun deleteSearchHistoryEntry(profileId: Long, query: String)

    @Query("DELETE FROM SEARCH_HISTORY WHERE SERVER_PROFILE_ID = :profileId")
    suspend fun clearSearchHistory(profileId: Long)
}
