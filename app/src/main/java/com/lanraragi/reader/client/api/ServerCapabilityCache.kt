package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.data.LRRServerInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-server cache of facts learned from `GET /api/info`, so calls the spec
 * says to gate on those facts can avoid firing doomed requests and offline
 * features can honor server configuration.
 *
 * Tracks:
 * - `server_tracks_progress`: when false the server uses clientside progress
 *   tracking and `PUT /archives/{id}/progress/{page}` returns 400 — the spec
 *   explicitly says to check `/api/info` before calling that endpoint. Read by
 *   [LRRArchiveApi.updateProgress].
 * - `excluded_namespaces`: tag namespaces the admin excluded from suggestions
 *   and statistics. The server already filters its own `/api/database/stats`;
 *   this copy lets the offline reading-stats page apply the same exclusions to
 *   locally stored tag snapshots. Backed by an optional [Store] so the value
 *   survives process death (an `/api/info` round-trip only happens on connect).
 *
 * Keyed by base URL so multi-server setups stay correct; populated by
 * [LRRServerApi.getServerInfo].
 */
object ServerCapabilityCache {

    /** Durable backing for facts worth keeping across launches. */
    interface Store {
        fun loadExcludedNamespaces(baseUrl: String): Set<String>?
        fun saveExcludedNamespaces(baseUrl: String, namespaces: Set<String>)
    }

    private val tracksProgress = ConcurrentHashMap<String, Boolean>()
    private val excludedNamespaces = ConcurrentHashMap<String, Set<String>>()
    private val serverSummaries = ConcurrentHashMap<String, String>()

    @Volatile
    private var store: Store? = null

    /** Installed once at app boot; tests leave it null and stay in-memory. */
    fun attachStore(store: Store?) {
        this.store = store
    }

    /** Single entry point for everything `/api/info` teaches us about [baseUrl]. */
    fun recordServerInfo(baseUrl: String, info: LRRServerInfo) {
        setTracksProgress(baseUrl, info.serverTracksProgress)
        serverSummaries[baseUrl] = com.lanraragi.reader.diagnostics.Redactor.redact(summarize(info))
        setExcludedNamespaces(baseUrl, info.excludedNamespaces)
    }

    /**
     * One line describing the last `/api/info` seen for [baseUrl] in this process,
     * for the diagnostics bundle (audit 2026-10-04 C06). Leaves out the server
     * name and MOTD, which are user text; null if the server was not contacted.
     */
    fun describeServerInfo(baseUrl: String): String? = serverSummaries[baseUrl]

    internal fun summarize(info: LRRServerInfo): String =
        "version=${info.version} (${info.versionName}), hasPassword=${info.hasPassword}, " +
            "nofun=${info.nofunMode}, debug=${info.debugMode}, resizes=${info.serverResizesImages}, " +
            "tracksProgress=${info.serverTracksProgress}, archivesPerPage=${info.archivesPerPage}, " +
            "excludedNamespaces=${info.excludedNamespaces.size}"

    fun setTracksProgress(baseUrl: String, value: Boolean) {
        tracksProgress[baseUrl] = value
    }

    /**
     * @return whether the server at [baseUrl] tracks reading progress
     *   server-side, or null if no `/api/info` has been observed for it yet
     *   (in which case callers should proceed rather than assume).
     */
    fun tracksProgress(baseUrl: String): Boolean? = tracksProgress[baseUrl]

    /** Normalizes to trimmed lowercase (LANraragi tag namespaces are lowercase). */
    fun setExcludedNamespaces(baseUrl: String, namespaces: Collection<String>) {
        val normalized = namespaces.mapTo(LinkedHashSet()) { it.trim().lowercase() }
        normalized.remove("")
        excludedNamespaces[baseUrl] = normalized
        store?.saveExcludedNamespaces(baseUrl, normalized)
    }

    /**
     * @return the admin-excluded namespaces for [baseUrl] (possibly empty), or
     *   null when no `/api/info` has ever been observed for that server —
     *   neither in this process nor in the attached [Store].
     */
    fun excludedNamespaces(baseUrl: String): Set<String>? =
        excludedNamespaces[baseUrl]
            ?: store?.loadExcludedNamespaces(baseUrl)?.also { excludedNamespaces[baseUrl] = it }

    /** Drops in-memory facts only; an attached [Store] keeps its contents. */
    fun clear() {
        tracksProgress.clear()
        serverSummaries.clear()
        excludedNamespaces.clear()
    }
}
