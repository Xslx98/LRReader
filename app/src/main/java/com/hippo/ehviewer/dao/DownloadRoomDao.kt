package com.hippo.ehviewer.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for download-related tables: DOWNLOADS, DOWNLOAD_DIRNAME, DOWNLOAD_LABELS.
 */
@Dao
interface DownloadRoomDao {

    // --- DOWNLOADS ---

    @Query("SELECT * FROM DOWNLOADS ORDER BY TIME DESC")
    suspend fun getAllDownloadInfo(): List<DownloadInfo>

    /**
     * Observe all downloads reactively. Room invalidates the Flow whenever the
     * DOWNLOADS table changes (insert/update/delete).
     *
     * Note: Flow-returning Room queries must NOT be `suspend` — Room handles
     * the background threading internally.
     */
    @Query("SELECT * FROM DOWNLOADS ORDER BY TIME DESC")
    fun observeAllDownloads(): Flow<List<DownloadInfo>>

    /**
     * Observe downloads filtered by server profile reactively.
     */
    @Query("SELECT * FROM DOWNLOADS WHERE SERVER_PROFILE_ID = :profileId ORDER BY TIME DESC")
    fun observeDownloadsByServer(profileId: Long): Flow<List<DownloadInfo>>

    @Query("SELECT * FROM DOWNLOADS WHERE SERVER_PROFILE_ID = :profileId ORDER BY TIME DESC")
    suspend fun getDownloadInfoByServer(profileId: Long): List<DownloadInfo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(info: DownloadInfo)

    @Update
    suspend fun update(info: DownloadInfo)

    @Update
    suspend fun updateAll(list: List<DownloadInfo>)

    @Query("SELECT * FROM DOWNLOADS WHERE GID = :gid")
    suspend fun loadDownload(gid: Long): DownloadInfo?

    @Query("DELETE FROM DOWNLOADS WHERE GID = :gid")
    suspend fun deleteDownloadByKey(gid: Long)

    // --- DOWNLOAD_DIRNAME ---

    @Query("SELECT * FROM DOWNLOAD_DIRNAME WHERE GID = :gid")
    suspend fun loadDirname(gid: Long): DownloadDirname?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDirname(dirname: DownloadDirname)

    @Update
    suspend fun updateDirname(dirname: DownloadDirname)

    @Query("DELETE FROM DOWNLOAD_DIRNAME WHERE GID = :gid")
    suspend fun deleteDirnameByKey(gid: Long)

    @Query("DELETE FROM DOWNLOAD_DIRNAME")
    suspend fun deleteAllDirnames()

    // --- DOWNLOAD_LABELS ---

    @Query("SELECT * FROM DOWNLOAD_LABELS ORDER BY TIME ASC")
    suspend fun getAllDownloadLabels(): List<DownloadLabel>

    @Insert
    suspend fun insertLabel(label: DownloadLabel): Long

    @Update
    suspend fun updateLabel(label: DownloadLabel)

    @Update
    suspend fun updateLabels(list: List<DownloadLabel>)

    @Delete
    suspend fun deleteLabel(label: DownloadLabel)

    @Query("SELECT * FROM DOWNLOAD_LABELS WHERE LABEL = :label LIMIT 1")
    suspend fun findLabelByName(label: String): DownloadLabel?

    @Query("SELECT * FROM DOWNLOAD_LABELS ORDER BY TIME ASC LIMIT :limit OFFSET :offset")
    suspend fun getLabelsRange(offset: Int, limit: Int): List<DownloadLabel>
}
