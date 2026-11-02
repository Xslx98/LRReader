package com.lanraragi.reader.server

import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.domain.Archive
import okhttp3.OkHttpClient

/**
 * Which LANraragi server a call goes to: the profile, its base URL and the
 * client to send it with. The `LRR*Api` objects stay stateless and take
 * `conn.client, conn.baseUrl`; this value is how callers pick the pair
 * without reaching into [LRRAuthManager] and [ProfileLookupCache] themselves.
 */
data class ServerConnection(val profileId: Long, val baseUrl: String, val client: OkHttpClient)

object ServerConnections {

    /** The active server, or null when none is configured. */
    fun active(): ServerConnection? {
        val url = LRRAuthManager.getServerUrl() ?: return null
        return ServerConnection(LRRAuthManager.getActiveProfileId(), url, ServiceRegistry.networkModule.okHttpClient)
    }

    /**
     * The server that owns data of [profileId], with [resolveSourceBaseUrl]'s
     * rules: 0 is legacy data on the active server; an unknown id throws
     * [OrphanProfileException].
     */
    suspend fun forProfile(
        profileId: Long,
        cache: ProfileLookupCache = ServiceRegistry.dataModule.profileLookupCache,
    ): ServerConnection =
        ServerConnection(profileId, resolveSourceBaseUrl(profileId, cache), ServiceRegistry.networkModule.okHttpClient)

    /** The server [archive] came from (its stored source profile, not the active one). */
    suspend fun forArchive(archive: Archive): ServerConnection = forProfile(archive.serverProfileId)
}
