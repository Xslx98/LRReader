package com.hippo.ehviewer.dao

import androidx.room.*

/**
 * Room DAO for the residual browsing-related table: QUICK_SEARCH.
 *
 * The HISTORY and LOCAL_FAVORITES tables are gone post-L1; their
 * subsystems live on `ARCHIVE_LOCAL_STATE` and are accessed via
 * [ArchiveLocalStateDao]. QUICK_SEARCH is unrelated to per-archive
 * state and stays on its own table.
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
}
