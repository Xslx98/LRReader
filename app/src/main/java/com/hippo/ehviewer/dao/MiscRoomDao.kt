package com.hippo.ehviewer.dao

import androidx.room.*

/**
 * Room DAO for misc tables: SERVER_PROFILES.
 */
@Dao
interface MiscRoomDao {

    // --- SERVER_PROFILES ---

    @Query("SELECT * FROM SERVER_PROFILES ORDER BY NAME ASC")
    suspend fun getAllServerProfiles(): List<ServerProfile>

    @Query("SELECT * FROM SERVER_PROFILES WHERE IS_ACTIVE = 1 LIMIT 1")
    suspend fun getActiveProfile(): ServerProfile?

    @Query("SELECT * FROM SERVER_PROFILES WHERE URL = :url LIMIT 1")
    suspend fun findProfileByUrl(url: String): ServerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServerProfile(profile: ServerProfile): Long

    @Update
    suspend fun updateServerProfile(profile: ServerProfile)

    @Delete
    suspend fun deleteServerProfile(profile: ServerProfile)

    @Query("UPDATE SERVER_PROFILES SET IS_ACTIVE = 0")
    suspend fun deactivateAllProfiles()
}
