package com.hippo.ehviewer.dao

import kotlinx.coroutines.flow.Flow

/**
 * Repository for server profile database operations, backed by [MiscRoomDao].
 *
 * Second domain repository extracted from [com.hippo.ehviewer.EhDB] (after
 * [HistoryRepository]), continuing the incremental God Object decomposition.
 *
 * Registered as a lazy val in [com.hippo.ehviewer.module.DataModule].
 */
class ProfileRepository(private val dao: MiscRoomDao) {

    suspend fun getAllProfiles(): List<ServerProfile> =
        dao.getAllServerProfiles()

    /**
     * Reactive list of profiles, sorted by name. Consumed by
     * `ProfileLookupCache` for sync host/id resolution from interceptors
     * and download workers.
     */
    fun observeAll(): Flow<List<ServerProfile>> =
        dao.observeAllProfiles()

    suspend fun getActiveProfile(): ServerProfile? =
        dao.getActiveProfile()

    suspend fun findByUrl(url: String): ServerProfile? =
        dao.findProfileByUrl(url)

    suspend fun findById(id: Long): ServerProfile? =
        dao.findProfileById(id)

    suspend fun insert(profile: ServerProfile): Long =
        dao.insertServerProfile(profile)

    suspend fun update(profile: ServerProfile) {
        dao.updateServerProfile(profile)
    }

    suspend fun delete(profile: ServerProfile) {
        dao.deleteServerProfile(profile)
    }

    suspend fun deactivateAll() {
        dao.deactivateAllProfiles()
    }

    /** Atomically make [id] the sole active profile. */
    suspend fun activateExclusive(id: Long) {
        dao.setActiveProfileExclusive(id)
    }
}
