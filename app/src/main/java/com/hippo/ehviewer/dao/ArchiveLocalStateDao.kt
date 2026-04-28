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
}
