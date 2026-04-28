package com.hippo.ehviewer.dao

import androidx.room.*

/**
 * Room DAO for the residual download-related tables: DOWNLOAD_DIRNAME
 * and DOWNLOAD_LABELS.
 *
 * The DOWNLOADS table itself is gone post-L1; download rows live on
 * `ARCHIVE_LOCAL_STATE` and are accessed via [ArchiveLocalStateDao].
 * The two tables that survive carry distinct concerns —
 * `arcid → on-disk dirname` mapping and label metadata respectively.
 */
@Dao
interface DownloadRoomDao {

    // --- DOWNLOAD_DIRNAME ---

    @Query("SELECT * FROM DOWNLOAD_DIRNAME WHERE ARCID = :arcid")
    suspend fun loadDirname(arcid: String): DownloadDirname?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDirname(dirname: DownloadDirname)

    @Update
    suspend fun updateDirname(dirname: DownloadDirname)

    @Query("DELETE FROM DOWNLOAD_DIRNAME WHERE ARCID = :arcid")
    suspend fun deleteDirnameByKey(arcid: String)

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

    @Insert
    suspend fun insertLabels(labels: List<DownloadLabel>): List<Long>

    @Query("SELECT * FROM DOWNLOAD_LABELS ORDER BY TIME ASC LIMIT :limit OFFSET :offset")
    suspend fun getLabelsRange(offset: Int, limit: Int): List<DownloadLabel>
}
