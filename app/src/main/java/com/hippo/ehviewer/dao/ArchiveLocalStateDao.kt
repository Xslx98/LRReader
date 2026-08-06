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

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.hippo.ehviewer.download.DownloadState
import kotlinx.coroutines.flow.Flow

/**
 * Value carrier for the merged download upserts
 * ([ArchiveLocalStateDao.upsertDownloadMerged] / [upsertDownloadBatchMerged]):
 * the repository's row-builder computes the merged `archive_json` from the
 * existing row it receives INSIDE the wrapping transaction, and batches
 * commit as one — N per-row transactions collapse into a single fsync.
 */
data class DownloadUpsertRow(
    val arcid: String,
    val serverProfileId: Long,
    val archiveJson: String,
    val downloadState: DownloadState,
    val downloadLegacy: Int,
    val downloadTime: Long,
    val downloadLabel: String?,
    val downloadArchiveUri: String?,
    val downloadRootUri: String?,
)

/**
 * Narrow projection for the downloads-list observer (audit #39).
 *
 * ARCHIVE_LOCAL_STATE is shared with history/favorites and Room invalidation
 * is table-granular: the downloads Flow requeries on EVERY table write. The
 * downstream `distinctUntilChanged` is what stands between those requeries
 * and an O(N) archive_json decode — and it only helps when rows compare
 * equal. `SELECT *` made every history-only write (per-page scroll-fraction
 * saves, history timestamps, favourite toggles) change the row value and
 * re-run the decode. This projection carries exactly the columns
 * `toDownloadInfoView` consumes.
 */
data class DownloadObservedRow(
    @ColumnInfo(name = "ARCID") val arcid: String,
    @ColumnInfo(name = "SERVER_PROFILE_ID") val serverProfileId: Long,
    @ColumnInfo(name = "ARCHIVE_JSON") val archiveJson: String,
    @ColumnInfo(name = "DOWNLOAD_STATE") val downloadState: DownloadState?,
    @ColumnInfo(name = "DOWNLOAD_LEGACY") val downloadLegacy: Int,
    @ColumnInfo(name = "DOWNLOAD_TIME") val downloadTime: Long?,
    @ColumnInfo(name = "DOWNLOAD_LABEL") val downloadLabel: String?,
    @ColumnInfo(name = "DOWNLOAD_ARCHIVE_URI") val downloadArchiveUri: String?,
    @ColumnInfo(name = "DOWNLOAD_ROOT_URI") val downloadRootUri: String?,
    @ColumnInfo(name = "DOWNLOAD_TANK_ID") val downloadTankId: String?,
)

/** Value carrier for [ArchiveLocalStateDao.upsertHistoryBatch]. */
data class HistoryUpsertRow(
    val arcid: String,
    val serverProfileId: Long,
    val archiveJson: String,
    val historyTime: Long,
    val historyMode: Int,
)

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

    /**
     * REPLACE = DELETE + INSERT, which resets *every* column of an existing
     * row to the values in [state] — including the sibling-subsystem columns
     * (DOWNLOAD_STATE / HISTORY_TIME / FAVORITE_TIME / archive_json) that the
     * caller didn't intend to touch. Production code must never round-trip a
     * partial row through here; use [update] or a read-modify-write merge
     * instead. Retained only as a test-seeding convenience for fresh inserts.
     */
    @Deprecated("REPLACE wipes sibling-subsystem columns; use update() or a merge-write")
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ArchiveLocalState)

    @Update
    suspend fun update(state: ArchiveLocalState)

    @Query("SELECT * FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :profileId")
    suspend fun loadByArcidAndProfile(arcid: String, profileId: Long): ArchiveLocalState?

    @Query("DELETE FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid")
    suspend fun deleteByArcid(arcid: String)

    // ── Download subsystem ─────────────────────────────────────

    @Query(
        "SELECT ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, DOWNLOAD_STATE, " +
            "DOWNLOAD_LEGACY, DOWNLOAD_TIME, DOWNLOAD_LABEL, " +
            "DOWNLOAD_ARCHIVE_URI, DOWNLOAD_ROOT_URI, DOWNLOAD_TANK_ID " +
            "FROM ARCHIVE_LOCAL_STATE " +
            "WHERE DOWNLOAD_STATE IS NOT NULL " +
            "ORDER BY DOWNLOAD_TIME DESC"
    )
    fun observeAllDownloads(): Flow<List<DownloadObservedRow>>

    // ── Tank download grouping (Track 2) ───────────────────────────

    /** Tag/untag one download row's tank membership. Null clears the tag. */
    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET DOWNLOAD_TANK_ID = :tankId " +
            "WHERE ARCID = :arcid AND DOWNLOAD_STATE IS NOT NULL"
    )
    suspend fun setDownloadTankId(arcid: String, tankId: String?)

    /** Untag every member of a dissolved tank in one statement. */
    @Query("UPDATE ARCHIVE_LOCAL_STATE SET DOWNLOAD_TANK_ID = NULL WHERE DOWNLOAD_TANK_ID = :tankId")
    suspend fun clearDownloadTankId(tankId: String)

    @Query(
        "SELECT * FROM ARCHIVE_LOCAL_STATE " +
            "WHERE DOWNLOAD_TANK_ID = :tankId AND DOWNLOAD_STATE IS NOT NULL"
    )
    suspend fun getDownloadsByTank(tankId: String): List<ArchiveLocalState>

    @Query(
        "SELECT * FROM ARCHIVE_LOCAL_STATE " +
            "WHERE ARCID IN (:arcids) AND DOWNLOAD_STATE IS NOT NULL"
    )
    suspend fun getDownloadsByArcids(arcids: List<String>): List<ArchiveLocalState>

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

    @Query(
        "SELECT COUNT(*) FROM ARCHIVE_LOCAL_STATE " +
            "WHERE HISTORY_TIME IS NOT NULL AND SERVER_PROFILE_ID = :profileId"
    )
    suspend fun countHistoryForProfile(profileId: Long): Int

    // ── Favorite subsystem ─────────────────────────────────────

    @Query(
        "SELECT * FROM ARCHIVE_LOCAL_STATE " +
            "WHERE FAVORITE_TIME IS NOT NULL " +
            "ORDER BY FAVORITE_TIME DESC"
    )
    suspend fun getAllFavorites(): List<ArchiveLocalState>

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
            "WHERE ARCID = :arcid AND DOWNLOAD_STATE IS NOT NULL"
    )
    suspend fun clearDownloadSubsystem(arcid: String)

    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET HISTORY_TIME = NULL, HISTORY_MODE = 0, HISTORY_SCROLL_FRACTION = NULL " +
            "WHERE HISTORY_TIME IS NOT NULL"
    )
    suspend fun clearAllHistorySubsystems()

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

    /**
     * Per-profile variant of [clearHistorySubsystemBeyond]: keeps only the top [maxCount]
     * history rows for [profileId]. The history list is shown per active profile, so a
     * global trim let heavy reading on one profile evict another profile's still-visible
     * history; this bounds each profile independently.
     */
    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE " +
            "SET HISTORY_TIME = NULL, HISTORY_MODE = 0, HISTORY_SCROLL_FRACTION = NULL " +
            "WHERE HISTORY_TIME IS NOT NULL AND SERVER_PROFILE_ID = :profileId " +
            "AND ARCID NOT IN (" +
            "  SELECT ARCID FROM ARCHIVE_LOCAL_STATE " +
            "  WHERE HISTORY_TIME IS NOT NULL AND SERVER_PROFILE_ID = :profileId " +
            "  ORDER BY HISTORY_TIME DESC LIMIT :maxCount" +
            ")"
    )
    suspend fun clearHistorySubsystemBeyondForProfile(profileId: Long, maxCount: Int)

    // ── Empty-row collapse ─────────────────────────────────────

    @Query(
        "DELETE FROM ARCHIVE_LOCAL_STATE " +
            "WHERE DOWNLOAD_STATE IS NULL " +
            "AND HISTORY_TIME IS NULL " +
            "AND FAVORITE_TIME IS NULL"
    )
    suspend fun deleteAllEmptyRows()

    // ── Targeted column updates ────────────────────────────────

    @Query("UPDATE ARCHIVE_LOCAL_STATE SET DOWNLOAD_TIME = :time WHERE ARCID = :arcid AND DOWNLOAD_STATE IS NOT NULL")
    suspend fun updateDownloadTime(arcid: String, time: Long)

    // ── Cross-subsystem-safe upsert pairs ──────────────────────
    //
    // INSERT-OR-IGNORE-then-UPDATE preserves columns belonging to
    // other subsystems on the same arcid. Repositories must call these
    // through the @Transaction pair-wrappers further below (DB-4) —
    // never as two bare statements — and must NOT add their own
    // `withTransaction` (which would put the calling coroutine in
    // Room's transaction dispatcher and trip the invalidation-tracker
    // assertion when the table is being observed by a Flow on the same
    // coroutine scope; Room's generated @Transaction wrapper does not
    // have that problem).

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
            "ARCHIVE_JSON = :archiveJson, " +
            "DOWNLOAD_STATE = :downloadState, " +
            "DOWNLOAD_LEGACY = :downloadLegacy, " +
            "DOWNLOAD_TIME = :downloadTime, " +
            "DOWNLOAD_LABEL = :downloadLabel, " +
            "DOWNLOAD_ARCHIVE_URI = :downloadArchiveUri, " +
            "DOWNLOAD_ROOT_URI = :downloadRootUri " +
            "WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :serverProfileId"
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
    @Query("SELECT DOWNLOAD_ROOT_URI FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid AND DOWNLOAD_STATE IS NOT NULL")
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

    /**
     * Update the history columns of the `(arcid, serverProfileId)` row.
     * With the composite primary key each profile owns its own row, so a
     * history write for a mirror copy read through another profile lands
     * on that profile's row and never touches the download row of a
     * different profile — the `c72cc28d` CASE guard that used to protect
     * a download-owned `SERVER_PROFILE_ID` is no longer needed (retired
     * with ADR-003).
     */
    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET " +
            "ARCHIVE_JSON = :archiveJson, " +
            "HISTORY_TIME = :historyTime, " +
            "HISTORY_MODE = :historyMode " +
            "WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :serverProfileId"
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

    /**
     * Update the favorite columns of the `(arcid, serverProfileId)` row.
     * Per-profile rows make the old download-ownership CASE guard
     * unnecessary (see [updateHistoryFields]).
     */
    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET " +
            "ARCHIVE_JSON = :archiveJson, " +
            "FAVORITE_TIME = :favoriteTime " +
            "WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :serverProfileId"
    )
    suspend fun updateFavoriteFields(
        arcid: String,
        serverProfileId: Long,
        archiveJson: String,
        favoriteTime: Long,
    )

    // ── Composite-key (ARCID, SERVER_PROFILE_ID) additions (ADR-003) ──
    //
    // Profile-scoped variants for the per-(arcid, profile) subsystems
    // (history / favorite / scroll-fraction) and download-predicate
    // variants for the arcid-unique download row. Callers migrate onto
    // these in later commits; the single-key methods above are removed
    // once unreferenced.

    /** The single download row for [arcid] (the "<=1 download per arcid" invariant). */
    @Query("SELECT * FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid AND DOWNLOAD_STATE IS NOT NULL LIMIT 1")
    suspend fun loadDownloadRowByArcid(arcid: String): ArchiveLocalState?

    /**
     * Narrow projection of [loadDownloadRowByArcid] for callers that only
     * need the owning profile id (the removal paths): skips marshalling the
     * potentially large ARCHIVE_JSON blob per row.
     */
    @Query(
        "SELECT SERVER_PROFILE_ID FROM ARCHIVE_LOCAL_STATE " +
            "WHERE ARCID = :arcid AND DOWNLOAD_STATE IS NOT NULL LIMIT 1"
    )
    suspend fun getDownloadProfileIdByArcid(arcid: String): Long?

    @Query("UPDATE ARCHIVE_LOCAL_STATE SET ARCHIVE_JSON = :archiveJson WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :profileId")
    suspend fun updateArchiveJsonForProfile(arcid: String, profileId: Long, archiveJson: String)

    @Query("UPDATE ARCHIVE_LOCAL_STATE SET ARCHIVE_JSON = :archiveJson WHERE ARCID = :arcid AND DOWNLOAD_STATE IS NOT NULL")
    suspend fun updateArchiveJsonForDownload(arcid: String, archiveJson: String)

    @Query("SELECT COUNT(*) FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :profileId AND FAVORITE_TIME IS NOT NULL")
    suspend fun favoriteCountForProfile(arcid: String, profileId: Long): Int

    @Query(
        "UPDATE ARCHIVE_LOCAL_STATE SET HISTORY_TIME = NULL, HISTORY_MODE = 0, HISTORY_SCROLL_FRACTION = NULL " +
            "WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :profileId"
    )
    suspend fun clearHistorySubsystemForProfile(arcid: String, profileId: Long)

    @Query("UPDATE ARCHIVE_LOCAL_STATE SET FAVORITE_TIME = NULL WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :profileId")
    suspend fun clearFavoriteSubsystemForProfile(arcid: String, profileId: Long)

    @Query(
        "DELETE FROM ARCHIVE_LOCAL_STATE " +
            "WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :profileId " +
            "AND DOWNLOAD_STATE IS NULL AND HISTORY_TIME IS NULL AND FAVORITE_TIME IS NULL"
    )
    suspend fun deleteIfNoSubsystemForProfile(arcid: String, profileId: Long)

    @Query("UPDATE ARCHIVE_LOCAL_STATE SET HISTORY_SCROLL_FRACTION = :fraction WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :profileId")
    suspend fun updateHistoryScrollFractionForProfile(arcid: String, profileId: Long, fraction: Float?)

    @Query("SELECT HISTORY_SCROLL_FRACTION FROM ARCHIVE_LOCAL_STATE WHERE ARCID = :arcid AND SERVER_PROFILE_ID = :profileId")
    suspend fun getHistoryScrollFractionForProfile(arcid: String, profileId: Long): Float?

    // ── Transactional statement pairs (DB-4) ───────────────────
    //
    // Each pair used to run as two independent statements; an
    // interleaving writer could permanently drop a subsystem row.
    // Worst interleave: B insertOrIgnore(X) is a no-op against the
    // doomed row → A clear(X) → A deleteIfNoSubsystem(X) removes the
    // whole row → B update(X) matches 0 rows — B's subsystem write is
    // silently lost. Room's generated @Transaction wrapper serializes
    // each pair without putting the *calling* coroutine on the
    // transaction dispatcher, so the Flow-observer concern above does
    // not apply.

    @Transaction
    suspend fun upsertHistory(
        arcid: String,
        serverProfileId: Long,
        archiveJson: String,
        historyTime: Long,
        historyMode: Int,
    ) {
        insertOrIgnoreHistory(arcid, serverProfileId, archiveJson, historyTime, historyMode)
        updateHistoryFields(arcid, serverProfileId, archiveJson, historyTime, historyMode)
    }

    @Suppress("LongParameterList")
    @Transaction
    suspend fun upsertDownload(
        arcid: String,
        serverProfileId: Long,
        archiveJson: String,
        downloadState: DownloadState,
        downloadLegacy: Int,
        downloadTime: Long,
        downloadLabel: String?,
        downloadArchiveUri: String?,
        downloadRootUri: String?,
    ) {
        insertOrIgnoreDownload(
            arcid, serverProfileId, archiveJson, downloadState,
            downloadLegacy, downloadTime, downloadLabel, downloadArchiveUri, downloadRootUri
        )
        updateDownloadFields(
            arcid, serverProfileId, archiveJson, downloadState,
            downloadLegacy, downloadTime, downloadLabel, downloadArchiveUri, downloadRootUri
        )
    }

    @Transaction
    suspend fun clearHistoryAndPruneForProfile(arcid: String, profileId: Long) {
        clearHistorySubsystemForProfile(arcid, profileId)
        deleteIfNoSubsystemForProfile(arcid, profileId)
    }

    @Transaction
    suspend fun clearFavoriteAndPruneForProfile(arcid: String, profileId: Long) {
        clearFavoriteSubsystemForProfile(arcid, profileId)
        deleteIfNoSubsystemForProfile(arcid, profileId)
    }

    @Transaction
    suspend fun clearDownloadAndPruneForProfile(arcid: String, profileId: Long) {
        clearDownloadSubsystem(arcid)
        deleteIfNoSubsystemForProfile(arcid, profileId)
    }

    @Transaction
    suspend fun clearAllHistoryAndPruneEmptyRows() {
        clearAllHistorySubsystems()
        deleteAllEmptyRows()
    }

    // ── Merged download upserts ────────────────────────────────
    //
    // The download upsert's archive_json merge used to READ the existing row
    // outside any transaction, compute the merged json, then write it via
    // upsertDownload — a lost-update window: a history/detail write landing
    // between the read and the UPDATE was silently overwritten with the
    // stale merge result. These wrappers pull the read into the same
    // generated transaction as the write. The row-builder lambda runs inside
    // the transaction; keep it pure (no dispatcher hops).

    @Transaction
    suspend fun upsertDownloadMerged(
        info: DownloadInfo,
        build: suspend (DownloadInfo, ArchiveLocalState?) -> DownloadUpsertRow,
    ) {
        val existing = loadByArcidAndProfile(info.arcid, info.serverProfileId)
        val r = build(info, existing)
        upsertDownload(
            r.arcid, r.serverProfileId, r.archiveJson, r.downloadState,
            r.downloadLegacy, r.downloadTime, r.downloadLabel,
            r.downloadArchiveUri, r.downloadRootUri
        )
    }

    @Transaction
    suspend fun upsertDownloadBatchMerged(
        infos: List<DownloadInfo>,
        build: suspend (DownloadInfo, ArchiveLocalState?) -> DownloadUpsertRow,
    ) {
        for (info in infos) {
            upsertDownloadMerged(info, build)
        }
    }

    // ── Batch wrappers ─────────────────────────────────────────
    //
    // Bulk operations (batch download add, batch remove, legacy history
    // import) used to loop over the per-row @Transaction wrappers — N
    // commits/fsyncs for an N-row batch. These wrap the whole loop in ONE
    // generated transaction. Room's @Transaction nesting is re-entrant, so
    // the inner per-row wrappers join the outer transaction. Same
    // Flow-observer rationale as the pair-wrappers above: this must stay a
    // DAO @Transaction method, never repository-level withTransaction.

    @Transaction
    suspend fun upsertDownloadBatch(rows: List<DownloadUpsertRow>) {
        for (r in rows) {
            upsertDownload(
                r.arcid, r.serverProfileId, r.archiveJson, r.downloadState,
                r.downloadLegacy, r.downloadTime, r.downloadLabel,
                r.downloadArchiveUri, r.downloadRootUri
            )
        }
    }

    @Transaction
    suspend fun upsertHistoryBatch(rows: List<HistoryUpsertRow>) {
        for (r in rows) {
            upsertHistory(r.arcid, r.serverProfileId, r.archiveJson, r.historyTime, r.historyMode)
        }
    }

    @Transaction
    suspend fun clearDownloadAndPruneBatch(arcids: List<String>) {
        for (arcid in arcids) {
            val pid = getDownloadProfileIdByArcid(arcid) ?: continue
            clearDownloadSubsystem(arcid)
            deleteIfNoSubsystemForProfile(arcid, pid)
        }
    }

    @Transaction
    suspend fun trimHistoryForProfile(profileId: Long, maxCount: Int) {
        // Runs on every archive open (putHistoryInfo), and the common case
        // is an already-bounded history. Only pay for the correlated NOT-IN
        // subquery + the un-indexed empty-row scan when a trim can actually
        // remove something. deleteAllEmptyRows has nothing to collapse when
        // no history column was cleared — every other clear path prunes its
        // own rows.
        if (countHistoryForProfile(profileId) <= maxCount) return
        clearHistorySubsystemBeyondForProfile(profileId, maxCount)
        deleteAllEmptyRows()
    }
}
