package com.lanraragi.reader.client.api

import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.dao.ServerProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Synchronously-readable snapshot of all configured [ServerProfile]s.
 *
 * Why this exists: OkHttp interceptors run on dispatcher threads with no
 * suspending capability, and the download worker needs to resolve a
 * profile by id at construction time without blocking on a DB read. Both
 * paths require a sync lookup. The cache subscribes to the reactive
 * [ProfileRepository.observeAll] flow on the application scope and keeps
 * a `@Volatile` snapshot reachable from any thread.
 *
 * Lookups never touch Room — they walk the in-memory snapshot. The first
 * emission lands a few hundred microseconds after construction; callers
 * that need to wait for it (e.g. the download worker on cold start) call
 * [awaitInitialized] from a coroutine context.
 *
 * Inserted, edited or deleted profiles propagate automatically because
 * Room's flow re-emits on every write to the SERVER_PROFILES table.
 */
class ProfileLookupCache(
    repo: ProfileRepository,
    scope: CoroutineScope,
) {
    private val _snapshot = MutableStateFlow<List<ServerProfile>>(emptyList())

    /** Observable snapshot — UI layers consume this for downloads-list badges. */
    val snapshot: StateFlow<List<ServerProfile>> = _snapshot.asStateFlow()

    /**
     * Profiles paired with their pre-parsed configured URL, refreshed once
     * per Room emission. Both network interceptors resolve candidates on
     * EVERY request; parsing each profile URL per lookup was N `HttpUrl`
     * parses × 2 interceptors per request. Profiles whose URL fails to
     * parse are absent here — identical to the old per-lookup `?: continue`
     * behaviour (they can never match a request anyway).
     */
    @Volatile
    private var parsedProfiles: List<ProfileUrlCandidate> = emptyList()

    private val initialized = CompletableDeferred<Unit>()

    init {
        scope.launch {
            repo.observeAll().collect { list ->
                // Parse before publishing _snapshot so a reader woken by the
                // snapshot change never sees a stale parsed list.
                parsedProfiles = list.mapNotNull { profile ->
                    profile.url.toHttpUrlOrNull()?.let { ProfileUrlCandidate(profile, it) }
                }
                _snapshot.value = list
                if (!initialized.isCompleted) initialized.complete(Unit)
            }
        }
    }

    /**
     * Suspend until the first emission lands. Used by paths that must
     * not race the cache's cold start (download worker starting before
     * the initial flow tick).
     */
    suspend fun awaitInitialized(): Unit = initialized.await()

    /** Returns the profile with the given id, or null if not present. */
    fun findById(id: Long): ServerProfile? =
        _snapshot.value.firstOrNull { it.id == id }

    /**
     * Returns the profile whose configured URL matches [requestUrl] on
     * host + port + scheme, or null if no profile matches. Match logic
     * mirrors [matchesConfiguredServer]: pure string equality on the
     * normalised HttpUrl fields, no DNS, no I/O.
     */
    fun findByRequestUrl(requestUrl: HttpUrl): ServerProfile? {
        for (candidate in parsedProfiles) {
            if (requestUrl.host == candidate.url.host &&
                requestUrl.port == candidate.url.port &&
                requestUrl.scheme == candidate.url.scheme
            ) {
                return candidate.profile
            }
        }
        return null
    }

    /**
     * Returns every profile whose configured URL matches [host] + [port]
     * regardless of scheme. Used to detect scheme-downgrade attempts: if
     * the request URL matches a profile on host + port but not on scheme,
     * treat it as a credential-downgrade attempt before injecting the
     * bearer token.
     */
    fun findCandidatesByHostPort(host: String, port: Int): List<ServerProfile> =
        findParsedCandidatesByHostPort(host, port).map { it.profile }

    /**
     * Parsed-URL variant of [findCandidatesByHostPort] for the network
     * interceptors, which need the candidate's scheme (and suspicious-
     * component fields) — hands back the pre-parsed [HttpUrl] so the hot
     * path never re-parses profile URLs.
     */
    fun findParsedCandidatesByHostPort(host: String, port: Int): List<ProfileUrlCandidate> {
        val out = mutableListOf<ProfileUrlCandidate>()
        for (candidate in parsedProfiles) {
            if (host == candidate.url.host && port == candidate.url.port) {
                out.add(candidate)
            }
        }
        return out
    }
}

/** A [ServerProfile] paired with its pre-parsed configured [HttpUrl]. */
data class ProfileUrlCandidate(
    val profile: ServerProfile,
    val url: HttpUrl,
)
