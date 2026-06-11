package com.hippo.ehviewer.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for misc tables: SERVER_PROFILES.
 */
@Dao
interface MiscRoomDao {

    // --- SERVER_PROFILES ---

    @Query("SELECT * FROM SERVER_PROFILES ORDER BY NAME ASC")
    suspend fun getAllServerProfiles(): List<ServerProfile>

    /**
     * Reactive variant of [getAllServerProfiles]. Used by `ProfileLookupCache`
     * to keep an in-memory snapshot fresh — interceptors and the download
     * worker need synchronous lookups by id / host that cannot await DB I/O.
     */
    @Query("SELECT * FROM SERVER_PROFILES ORDER BY NAME ASC")
    fun observeAllProfiles(): Flow<List<ServerProfile>>

    @Query("SELECT * FROM SERVER_PROFILES WHERE IS_ACTIVE = 1 LIMIT 1")
    suspend fun getActiveProfile(): ServerProfile?

    @Query("SELECT * FROM SERVER_PROFILES WHERE URL = :url LIMIT 1")
    suspend fun findProfileByUrl(url: String): ServerProfile?

    @Query("SELECT * FROM SERVER_PROFILES WHERE ID = :id LIMIT 1")
    suspend fun findProfileById(id: Long): ServerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServerProfile(profile: ServerProfile): Long

    @Update
    suspend fun updateServerProfile(profile: ServerProfile)

    @Delete
    suspend fun deleteServerProfile(profile: ServerProfile)

    @Query("UPDATE SERVER_PROFILES SET IS_ACTIVE = 0")
    suspend fun deactivateAllProfiles()

    /** Atomically make [id] the sole active profile — avoids the deactivate-then-activate
     *  window where a crash could leave zero (or two) active profiles. */
    @Query("UPDATE SERVER_PROFILES SET IS_ACTIVE = CASE WHEN ID = :id THEN 1 ELSE 0 END")
    suspend fun setActiveProfileExclusive(id: Long)
}
