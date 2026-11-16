package com.lanraragi.reader.server

import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.ServerProfile
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.lanraragi.reader.client.api.await

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
 *
 * It is also the one owner of the active server: [activeServer] is the
 * `IS_ACTIVE` row of the same emission, so the active server and the
 * profile list can never disagree. Nothing else stores it.
 *
 * @param scope where the profile flow is collected; must not be the main
 *   thread, because main-thread readers block on the first emission
 *   ([activeServerBlocking])
 */
class ProfileLookupCache(
    profiles: Flow<List<ServerProfile>>,
    scope: CoroutineScope,
) {
    constructor(repo: ProfileRepository, scope: CoroutineScope) : this(repo.observeAll(), scope)

    private val _snapshot = MutableStateFlow<List<ServerProfile>?>(null)

    /**
     * Observable snapshot, null until the first Room emission (or until the
     * profile flow fails or stops without one). UI badges collect it so a
     * row bound at cold start is re-bound once the profiles are known, and
     * after every rename or delete.
     */
    val snapshot: StateFlow<List<ServerProfile>?> = _snapshot.asStateFlow()

    /**
     * Whether the first snapshot has landed. Until then a [findById] miss
     * says nothing about whether the profile exists, so UI must not call it
     * deleted. Also true once the profile flow has failed or stopped
     * without an emission: the snapshot is then empty and every lookup
     * misses, as [awaitInitialized] callers see it.
     */
    val isLoaded: Boolean get() = _snapshot.value != null

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

    private val _activeServer = MutableStateFlow<ActiveServer?>(null)

    /** The active server (the `IS_ACTIVE` profile), null when there is none or before the first emission. */
    val activeServer: StateFlow<ActiveServer?> = _activeServer.asStateFlow()

    /**
     * Bumped whenever [activeServer] changes value (switch, add, edit of the
     * active profile's address, name or cleartext consent, delete).
     */
    @Volatile
    var activeRevision: Long = 0L
        private set

    private val initialized = CompletableDeferred<Unit>()

    /** [initialized] for blocking readers. */
    private val loadedLatch = CountDownLatch(1)

    @Volatile
    private var mainWaitTimedOut = false

    @Volatile
    private var backgroundWaitTimedOut = false

    init {
        scope.launch {
            try {
                profiles.collect { list ->
                    // Parse before publishing _snapshot so a reader woken by the
                    // snapshot change never sees a stale parsed list.
                    parsedProfiles = list.mapNotNull { profile ->
                        profile.url.toHttpUrlOrNull()?.let { ProfileUrlCandidate(profile, it) }
                    }
                    val active = list.firstOrNull { it.isActive }?.let(ActiveServer::of)
                    if (active != _activeServer.value) activeRevision++
                    _activeServer.value = active
                    _snapshot.value = list
                    markLoaded()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Server profile observation failed", e)
            } finally {
                // Release awaiters even when the flow ends without an emission
                // (audit 2026-10-06 N8): they then see the current, possibly
                // empty, snapshot instead of waiting forever.
                if (_snapshot.value == null) _snapshot.value = emptyList()
                markLoaded()
            }
        }
    }

    private fun markLoaded() {
        if (!initialized.isCompleted) initialized.complete(Unit)
        loadedLatch.countDown()
    }

    /** [activeServer] for synchronous readers, after [awaitLoadedBlocking]. */
    fun activeServerBlocking(): ActiveServer? {
        awaitLoadedBlocking()
        return _activeServer.value
    }

    /**
     * Blocks until the first emission, bounded like the secure store's gate
     * (below the ANR limit on the main thread). After a reader gave up,
     * later readers on that kind of thread stop waiting and see an empty
     * cache (no active server, no profile matches) until the first emission.
     */
    fun awaitLoadedBlocking() {
        if (loadedLatch.count == 0L) return
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        if (if (onMain) mainWaitTimedOut else backgroundWaitTimedOut) return
        val timeoutMs = if (onMain) MAIN_THREAD_WAIT_MS else BACKGROUND_WAIT_MS
        if (!loadedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            if (onMain) mainWaitTimedOut = true else backgroundWaitTimedOut = true
            Log.e(TAG, "Server profiles still loading after $timeoutMs ms; continuing without them")
        }
    }

    /**
     * Waits (bounded) until [activeServer] satisfies [predicate]. A profile
     * write returns before Room re-emits; a commit awaits this so callers
     * reading the active server right after it see the new one.
     */
    suspend fun awaitActive(predicate: (ActiveServer?) -> Boolean) {
        withTimeoutOrNull(COMMIT_VISIBLE_WAIT_MS) { activeServer.first(predicate); true }
            ?: Log.e(TAG, "Active server change not observed within $COMMIT_VISIBLE_WAIT_MS ms")
    }

    /**
     * Suspend until the first emission lands, or until the profile flow has
     * failed or stopped without one (the snapshot is then empty and lookups
     * miss). Used by paths that must not race the cache's cold start
     * (download worker starting before the initial flow tick).
     */
    suspend fun awaitInitialized(): Unit = initialized.await()

    /** Returns the profile with the given id, or null if not present. */
    fun findById(id: Long): ServerProfile? =
        _snapshot.value?.firstOrNull { it.id == id }

    /**
     * Every profile whose configured URL matches [host] + [port], regardless
     * of scheme, with its pre-parsed [HttpUrl]. The network interceptors pick
     * the key from the scheme-matching candidate and treat a host:port match
     * on another scheme as a credential-downgrade attempt; handing back the
     * parsed URL keeps profile URLs from being re-parsed on the hot path.
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

    private companion object {
        const val TAG = "ProfileLookupCache"
        const val MAIN_THREAD_WAIT_MS = 2_000L
        const val BACKGROUND_WAIT_MS = 10_000L
        const val COMMIT_VISIBLE_WAIT_MS = 5_000L
    }
}

/**
 * The active server as one value: which profile, where it is, and whether
 * the user consented to plain HTTP for it. Its API key stays in the secure
 * store ([ServerSession.getApiKeyForProfile]).
 */
data class ActiveServer(
    val id: Long,
    /** Base URL without a trailing slash. */
    val url: String,
    val name: String,
    val allowCleartext: Boolean,
) {
    companion object {
        fun of(profile: ServerProfile) =
            ActiveServer(profile.id, profile.url.removeSuffix("/"), profile.name, profile.allowCleartext)
    }
}

/** A [ServerProfile] paired with its pre-parsed configured [HttpUrl]. */
data class ProfileUrlCandidate(
    val profile: ServerProfile,
    val url: HttpUrl,
)
