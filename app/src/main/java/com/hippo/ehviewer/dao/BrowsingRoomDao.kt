package com.hippo.ehviewer.dao

import androidx.room.*

/**
 * Room DAO for browsing-related tables: HISTORY, LOCAL_FAVORITES, QUICK_SEARCH.
 */
@Dao
interface BrowsingRoomDao {

    // --- HISTORY ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(info: HistoryInfo)

    @Query("SELECT * FROM HISTORY ORDER BY TIME DESC")
    suspend fun getAllHistory(): List<HistoryInfo>

    @Query("SELECT * FROM HISTORY WHERE SERVER_PROFILE_ID = :profileId ORDER BY TIME DESC")
    suspend fun getHistoryByServer(profileId: Long): List<HistoryInfo>

    @Query("SELECT * FROM HISTORY ORDER BY TIME DESC LIMIT :limit")
    suspend fun getHistoryLimit(limit: Int): List<HistoryInfo>

    @Query("DELETE FROM HISTORY WHERE GID = :gid")
    suspend fun deleteHistoryByKey(gid: Long)

    @Query("DELETE FROM HISTORY")
    suspend fun deleteAllHistory()

    @Query("SELECT COUNT(*) FROM HISTORY")
    suspend fun countHistory(): Int

    @Query("DELETE FROM HISTORY WHERE GID NOT IN (SELECT GID FROM HISTORY ORDER BY TIME DESC LIMIT :maxCount)")
    suspend fun trimHistoryTo(maxCount: Int)

    @Query("SELECT * FROM HISTORY ORDER BY TIME ASC LIMIT 1")
    suspend fun getOldestHistory(): HistoryInfo?

    // --- LOCAL_FAVORITES ---

    @Query("SELECT * FROM LOCAL_FAVORITES ORDER BY TIME DESC")
    suspend fun getAllLocalFavorites(): List<LocalFavoriteInfo>

    @Query("SELECT * FROM LOCAL_FAVORITES WHERE TITLE LIKE :query ORDER BY TIME DESC")
    suspend fun searchLocalFavorites(query: String): List<LocalFavoriteInfo>

    @Query("SELECT * FROM LOCAL_FAVORITES WHERE GID = :gid")
    suspend fun loadLocalFavorite(gid: Long): LocalFavoriteInfo?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLocalFavorite(info: LocalFavoriteInfo)

    @Query("DELETE FROM LOCAL_FAVORITES WHERE GID = :gid")
    suspend fun deleteLocalFavoriteByKey(gid: Long)

    // --- QUICK_SEARCH ---

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
