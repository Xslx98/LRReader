package com.hippo.ehviewer.dao

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

    suspend fun getActiveProfile(): ServerProfile? =
        dao.getActiveProfile()

    suspend fun findByUrl(url: String): ServerProfile? =
        dao.findProfileByUrl(url)

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
}
