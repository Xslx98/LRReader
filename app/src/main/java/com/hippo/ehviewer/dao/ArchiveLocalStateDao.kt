/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hippo.ehviewer.download.DownloadState
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO over [ArchiveLocalState]. The single source of truth for the
 * download / history / local-favorite subsystems after L1.
 *
 * Conventions:
 * - Subsystem membership is filtered with `IS NOT NULL` on the
 *   subsystem's key column (see [ArchiveLocalState] for the predicates).
 * - Surface stays small in L1-1; L1-3 will extend this DAO with whatever
 *   the three repositories need to drop the legacy DAOs.
 */
@Dao
interface ArchiveLocalStateDao {

    // ── Row-level CRUD ─────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ArchiveLocalState)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<ArchiveLocalState>)

    @Update
    suspend fun update(state: ArchiveLocalState)

    @Query("SELECT * FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid")
    suspend fun loadByArcid(arcid: String): ArchiveLocalState?

    @Query("DELETE FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid")
    suspend fun deleteByArcid(arcid: String)

    // ── Download subsystem ─────────────────────────────────────

    @Query(
        "SELECT * FROM ARCHIVE_LOCAL_STATE " +
            "WHERE DOWNLOAD_STATE IS NOT NULL " +
            "ORDER BY DOWNLOAD_TIME DESC"
    )
    fun observeAllDownloads(): Flow<List<ArchiveLocalState>>

    @Query(
        "SELECT * FROM ARCHIVE_LOCAL_STATE " +
            "WHERE DOWNLOAD_STATE IS NOT NULL AND SERVER_PROFILE_ID = :profileId " +
            "ORDER BY DOWNLOAD_TIME DESC"
    )
    fun observeDownloadsByServer(profileId: Long): Flow<List<ArchiveLocalState>>

    @Query(
        "SELECT * FROM ARCHIVE_LOCAL_STATE " +
            "WHERE DOWNLOAD_STATE IS NOT NULL " +
            "ORDER BY DOWNLOAD_TIME DESC"
    )
    suspend fun getAllDownloads(): List<ArchiveLocalState>

    @Query(
        "SELECT * FROM ARCHIVE_LOCAL_STATE " +
            "WHERE DOWNLOAD_STATE IS NOT NULL AND SERVER_PROFILE_ID = :profileId " +
            "ORDER BY DOWNLOAD_TIME DESC"
    )
    suspend fun getDownloadsByServer(profileId: Long): List<ArchiveLocalState>

    /**
     * Reset transient WAIT (1) / DOWNLOAD (2) states to NONE (0). Run once at process
     * start: an in-flight download cannot survive process death, so any such persisted
     * row is a ghost that would otherwise render as "downloading" forever (frozen
     * progress, dead stop button). A NULL DOWNLOAD_STATE means "not a download", so the
     * predicate never touches history/favorite-only rows. Codes are the frozen
     * [com.hippo.ehviewer.download.DownloadState] integers.
     */
    @Query("UPDATE ARCHIVE_LOCAL_STATE SET DOWNLOAD_STATE = 0 WHERE DOWNLOAD_STATE = 1 OR DOWNLOAD_STATE = 2")
    suspend fun resetTransientDownloadStates()

    // ── History subsystem ──────────────────────────────────────

    @Query(
        "SELECT * FROM ARCHIVE_LOCAL_STATE " +
            "WHERE HISTORY_TIME IS NOT NULL " +
            "ORDER BY HISTORY_TIME DESC"
    )
    suspend fun getAllHistory(): List<ArchiveLocalState>

    @Query(
        "SELECT * FROM ARCHIVE_LOCAL_STATE " +
            "WHERE HISTORY_TIME IS NOT NULL AND SERVER_PROFILE_ID = :profileId " +
            "ORDER BY HISTORY_TIME DESC"
    )
    suspend fun getHistoryByServer(profileId: Long): List<ArchiveLocalState>

    // ── Favorite subsystem ─────────────────────────────────────

    @Query(
        "SELECT * FROM ARCHIVE_LOCAL_STATE " +
            "WHERE FAVORITE_TIME IS NOT NULL " +
            "ORDER BY FAVORITE_TIME DESC"
    )
    suspend fun getAllFavorites(): List<ArchiveLocalState>

    @Query("SELECT COUNT(*) FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid AND FAVORITE_TIME IS NOT NULL")
    suspend fun favoriteCount(arcid: String): Int

    // ── Subsystem-scoped writes ────────────────────────────────
    //
    // Each "clear" sets the subsystem's columns to their absent
    // sentinel (NULL on the key column, defaults on the rest). The row
    // itself is left in place; pair with [deleteIfNoSubsystem] to
    // collapse a now-empty row.

    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET " +
            "DOWNLOAD_STATE = NULL, DOWNLOAD_LEGACY = 0, DOWNLOAD_TIME = NULL, " +
            "DOWNLOAD_LABEL = NULL, DOWNLOAD_ARCHIVE_URI = NULL, " +
            "DOWNLOAD_ROOT_URI = NULL " +
            "WHERE ARCID = :arcid"
    )
    suspend fun clearDownloadSubsystem(arcid: String)

    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET HISTORY_TIME = NULL, HISTORY_MODE = 0, HISTORY_SCROLL_FRACTION = NULL " +
            "WHERE ARCID = :arcid"
    )
    suspend fun clearHistorySubsystem(arcid: String)

    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET HISTORY_TIME = NULL, HISTORY_MODE = 0, HISTORY_SCROLL_FRACTION = NULL " +
            "WHERE HISTORY_TIME IS NOT NULL"
    )
    suspend fun clearAllHistorySubsystems()

    @Query("UPDATE ARCHIVE_LOCAL_STATE SET FAVORITE_TIME = NULL WHERE ARCID = :arcid")
    suspend fun clearFavoriteSubsystem(arcid: String)

    /**
     * Trim history: clear the history subsystem on every row that
     * isn't in the top [maxCount] by HISTORY_TIME desc. Pair with
     * [deleteAllEmptyRows] to remove rows that lose their last
     * subsystem.
     */
    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE " +
            "SET HISTORY_TIME = NULL, HISTORY_MODE = 0, HISTORY_SCROLL_FRACTION = NULL " +
            "WHERE HISTORY_TIME IS NOT NULL " +
            "AND ARCID NOT IN (" +
            "  SELECT ARCID FROM ARCHIVE_LOCAL_STATE " +
            "  WHERE HISTORY_TIME IS NOT NULL " +
            "  ORDER BY HISTORY_TIME DESC LIMIT :maxCount" +
            ")"
    )
    suspend fun clearHistorySubsystemBeyond(maxCount: Int)

    // ── Empty-row collapse ─────────────────────────────────────

    @Query(
        "DELETE FROM ARCHIVE_LOCAL_STATE " +
            "WHERE ARCID = :arcid " +
            "AND DOWNLOAD_STATE IS NULL " +
            "AND HISTORY_TIME IS NULL " +
            "AND FAVORITE_TIME IS NULL"
    )
    suspend fun deleteIfNoSubsystem(arcid: String)

    @Query(
        "DELETE FROM ARCHIVE_LOCAL_STATE " +
            "WHERE DOWNLOAD_STATE IS NULL " +
            "AND HISTORY_TIME IS NULL " +
            "AND FAVORITE_TIME IS NULL"
    )
    suspend fun deleteAllEmptyRows()

    // ── Targeted column updates ────────────────────────────────

    @Query("UPDATE ARCHIVE_LOCAL_STATE SET DOWNLOAD_TIME = :time WHERE ARCID = :arcid")
    suspend fun updateDownloadTime(arcid: String, time: Long)

    @Query("UPDATE ARCHIVE_LOCAL_STATE SET ARCHIVE_JSON = :archiveJson WHERE ARCID = :arcid")
    suspend fun updateArchiveJson(arcid: String, archiveJson: String)

    /**
     * Update the per-archive intra-page scroll fraction. The UPDATE
     * is a no-op if no row exists for [arcid] at all. We deliberately
     * do *not* gate on `HISTORY_TIME IS NOT NULL`: the reader can be
     * launched directly from the downloads list (via
     * [com.hippo.ehviewer.ui.scene.download.DownloadGalleryOpenHelper])
     * which bypasses the detail page and therefore never calls
     * [HistoryRepository.putHistoryInfo] — in that case the row
     * exists with `DOWNLOAD_STATE` set but `HISTORY_TIME = NULL`.
     * The fraction is just a column; persisting it on whichever row
     * already exists is the right behavior. The accompanying
     * recordHistory call from GalleryActivity then upgrades the row
     * to history-subsystem membership in parallel.
     */
    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET HISTORY_SCROLL_FRACTION = :fraction " +
            "WHERE ARCID = :arcid"
    )
    suspend fun updateHistoryScrollFraction(arcid: String, fraction: Float?)

    @Query("SELECT HISTORY_SCROLL_FRACTION FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid")
    suspend fun getHistoryScrollFraction(arcid: String): Float?

    // ── Cross-subsystem-safe upsert pairs ──────────────────────
    //
    // INSERT-OR-IGNORE-then-UPDATE preserves columns belonging to
    // other subsystems on the same arcid. Each statement is a single
    // SQL operation, so callers don't need a `withTransaction` wrapper
    // (which would put the calling coroutine in Room's transaction
    // dispatcher and trip the invalidation-tracker assertion when the
    // table is being observed by a Flow on the same coroutine scope).

    @Suppress("LongParameterList")
    @Query(
        "INSERT OR IGNORE INTO ARCHIVE_LOCAL_STATE " +
            "(ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, DOWNLOAD_STATE, DOWNLOAD_LEGACY, " +
            "DOWNLOAD_TIME, DOWNLOAD_LABEL, DOWNLOAD_ARCHIVE_URI, DOWNLOAD_ROOT_URI) " +
            "VALUES (:arcid, :serverProfileId, :archiveJson, :downloadState, " +
            ":downloadLegacy, :downloadTime, :downloadLabel, :downloadArchiveUri, " +
            ":downloadRootUri)"
    )
    suspend fun insertOrIgnoreDownload(
        arcid: String,
        serverProfileId: Long,
        archiveJson: String,
        downloadState: DownloadState,
        downloadLegacy: Int,
        downloadTime: Long,
        downloadLabel: String?,
        downloadArchiveUri: String?,
        downloadRootUri: String?,
    )

    @Suppress("LongParameterList")
    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET " +
            "SERVER_PROFILE_ID = :serverProfileId, " +
            "ARCHIVE_JSON = :archiveJson, " +
            "DOWNLOAD_STATE = :downloadState, " +
            "DOWNLOAD_LEGACY = :downloadLegacy, " +
            "DOWNLOAD_TIME = :downloadTime, " +
            "DOWNLOAD_LABEL = :downloadLabel, " +
            "DOWNLOAD_ARCHIVE_URI = :downloadArchiveUri, " +
            "DOWNLOAD_ROOT_URI = :downloadRootUri " +
            "WHERE ARCID = :arcid"
    )
    suspend fun updateDownloadFields(
        arcid: String,
        serverProfileId: Long,
        archiveJson: String,
        downloadState: DownloadState,
        downloadLegacy: Int,
        downloadTime: Long,
        downloadLabel: String?,
        downloadArchiveUri: String?,
        downloadRootUri: String?,
    )

    /**
     * Backfill legacy download rows that pre-date the
     * DOWNLOAD_ROOT_URI column with the user's current download
     * location URI. Idempotent — only NULL rows are touched, so
     * subsequent boots have no work to do.
     */
    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET DOWNLOAD_ROOT_URI = :uri " +
            "WHERE DOWNLOAD_STATE IS NOT NULL AND DOWNLOAD_ROOT_URI IS NULL"
    )
    suspend fun backfillDownloadRootUri(uri: String)

    /**
     * Lightweight lookup used by SpiderDen / GalleryProvider on the
     * read path: resolve the download tree URI persisted with this
     * archive. Returns NULL when the archive has no download row OR
     * when the row pre-dates the v25→v26 backfill — the caller falls
     * back to `DownloadSettings.getDownloadLocation()` in that case.
     */
    @Query("SELECT DOWNLOAD_ROOT_URI FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid")
    suspend fun getDownloadRootUri(arcid: String): String?

    @Query(
        "INSERT OR IGNORE INTO ARCHIVE_LOCAL_STATE " +
            "(ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, HISTORY_TIME, HISTORY_MODE) " +
            "VALUES (:arcid, :serverProfileId, :archiveJson, :historyTime, :historyMode)"
    )
    suspend fun insertOrIgnoreHistory(
        arcid: String,
        serverProfileId: Long,
        archiveJson: String,
        historyTime: Long,
        historyMode: Int,
    )

    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET " +
            "SERVER_PROFILE_ID = :serverProfileId, " +
            "ARCHIVE_JSON = :archiveJson, " +
            "HISTORY_TIME = :historyTime, " +
            "HISTORY_MODE = :historyMode " +
            "WHERE ARCID = :arcid"
    )
    suspend fun updateHistoryFields(
        arcid: String,
        serverProfileId: Long,
        archiveJson: String,
        historyTime: Long,
        historyMode: Int,
    )

    @Query(
        "INSERT OR IGNORE INTO ARCHIVE_LOCAL_STATE " +
            "(ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, FAVORITE_TIME) " +
            "VALUES (:arcid, :serverProfileId, :archiveJson, :favoriteTime)"
    )
    suspend fun insertOrIgnoreFavorite(
        arcid: String,
        serverProfileId: Long,
        archiveJson: String,
        favoriteTime: Long,
    )

    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET " +
            "SERVER_PROFILE_ID = :serverProfileId, " +
            "ARCHIVE_JSON = :archiveJson, " +
            "FAVORITE_TIME = :favoriteTime " +
            "WHERE ARCID = :arcid"
    )
    suspend fun updateFavoriteFields(
        arcid: String,
        serverProfileId: Long,
        archiveJson: String,
        favoriteTime: Long,
    )
}
