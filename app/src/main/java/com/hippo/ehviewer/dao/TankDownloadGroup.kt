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
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One downloaded tankoubon: the identity + display name + ORDERED member
 * ids behind the downloads list's aggregated tank card. Member download
 * rows carry the matching [ArchiveLocalState.downloadTankId] tag; this
 * row exists so the card renders (name) and the whole-tank composite
 * session can be rebuilt OFFLINE in tank order (member pagecounts come
 * from the member rows' archive_json snapshots).
 *
 * [memberIdsJson] is a kotlinx-serialized `List<String>` of arcids in
 * tank order at download time. It is NOT a membership truth source — the
 * server owns membership; this is the offline snapshot the card reads.
 */
@Entity(tableName = "TANK_DOWNLOAD_GROUP")
data class TankDownloadGroup(
    @PrimaryKey
    @ColumnInfo(name = "TANK_ID")
    val tankId: String,

    @ColumnInfo(name = "SERVER_PROFILE_ID", defaultValue = "0")
    val serverProfileId: Long = 0L,

    @ColumnInfo(name = "NAME")
    val name: String,

    @ColumnInfo(name = "MEMBER_IDS_JSON")
    val memberIdsJson: String,

    @ColumnInfo(name = "CREATED_TIME", defaultValue = "0")
    val createdTime: Long = 0L,
)

@Dao
interface TankDownloadGroupDao {

    @Upsert
    suspend fun upsert(group: TankDownloadGroup)

    @Query("DELETE FROM TANK_DOWNLOAD_GROUP WHERE TANK_ID = :tankId")
    suspend fun delete(tankId: String)

    @Query("SELECT * FROM TANK_DOWNLOAD_GROUP WHERE TANK_ID = :tankId")
    suspend fun getById(tankId: String): TankDownloadGroup?

    @Query("SELECT * FROM TANK_DOWNLOAD_GROUP ORDER BY CREATED_TIME DESC")
    fun observeAll(): Flow<List<TankDownloadGroup>>

    @Query("SELECT * FROM TANK_DOWNLOAD_GROUP")
    suspend fun getAll(): List<TankDownloadGroup>
}
