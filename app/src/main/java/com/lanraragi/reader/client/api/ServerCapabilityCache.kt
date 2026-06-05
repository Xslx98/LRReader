package com.lanraragi.reader.client.api

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-server cache of capability flags learned from `GET /api/info`, so calls
 * the spec says to gate on those flags can avoid firing doomed requests.
 *
 * Currently tracks `server_tracks_progress`: when false the server uses
 * clientside progress tracking and `PUT /archives/{id}/progress/{page}` returns
 * 400 — the spec explicitly says to check `/api/info` before calling that
 * endpoint. Keyed by base URL so multi-server setups stay correct; populated by
 * [LRRServerApi.getServerInfo] and read by [LRRArchiveApi.updateProgress].
 */
object ServerCapabilityCache {

    private val tracksProgress = ConcurrentHashMap<String, Boolean>()

    fun setTracksProgress(baseUrl: String, value: Boolean) {
        tracksProgress[baseUrl] = value
    }

    /**
     * @return whether the server at [baseUrl] tracks reading progress
     *   server-side, or null if no `/api/info` has been observed for it yet
     *   (in which case callers should proceed rather than assume).
     */
    fun tracksProgress(baseUrl: String): Boolean? = tracksProgress[baseUrl]

    fun clear() = tracksProgress.clear()
}
