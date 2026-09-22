package com.lanraragi.reader.ui.scene.tankdetail

import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.ArchiveRatingChangedEvent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.TankCoverCacheStamp
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.TankoubonSupportGate
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.TagGroup
import com.lanraragi.reader.domain.mergeRatingIntoTags
import com.lanraragi.reader.domain.parseLrrTagString
import com.lanraragi.reader.domain.parseRatingFromTags
import com.lanraragi.reader.download.TankGroupReconciler
import com.lanraragi.reader.download.TankMembershipSync
import com.lanraragi.reader.gallery.TankPageMath
import com.lanraragi.reader.tankoubon.TankCategorySyncer
import com.lanraragi.reader.tankoubon.TankCoverChoiceStore
import com.lanraragi.reader.tankoubon.TankTagSyncer
import com.lanraragi.reader.ui.TankMembershipSyncFactory
import com.lanraragi.reader.ui.scene.gallery.detail.FavoriteState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * ViewModel for the tankoubon DETAIL page (spec 2026-09-22 §4): the page
 * that makes a tank look like a single archive. Loads
 * `GET /api/tankoubons/{id}/full` into a [TankDetailState] (members in
 * `archives` order, page totals/offsets, the tank's OWN tags and rating)
 * and the static-category heart state; when the fetch fails it falls back
 * to the downloaded-tank snapshot (`TANK_DOWNLOAD_GROUP` + member
 * `archive_json`) as an OFFLINE state with editing disabled.
 *
 * Member management (rename / reorder / remove / cover) stays in
 * [com.lanraragi.reader.ui.scene.TankoubonDetailViewModel]; this class
 * only owns what the detail page renders.
 */
class TankDetailViewModel : ViewModel() {

    /** Immutable render model of a loaded tank. */
    data class TankDetailState(
        val tankId: String,
        val profileId: Long,
        /** Source base URL; null in offline mode when resolution failed. */
        val baseUrl: String?,
        val name: String,
        val summary: String?,
        /** Raw tank tag string as the server holds it ("" when none). */
        val tags: String,
        /** Ordered members (server `archives` order), mapped with source context. */
        val members: List<Archive>,
        /** Global 1-indexed reading progress (0/1 = nothing meaningful). */
        val progress: Int,
        /** True = built from the local download snapshot; rating/category/tag editing disabled. */
        val offline: Boolean,
        /** First member as cover stand-in when the server reported no generated cover; null otherwise. */
        val coverFallbackMember: Archive?,
    ) {
        val memberIds: List<String> = members.map { it.arcid }

        /** Prefix-sum global page offsets, see [TankPageMath.pageOffsets]. */
        val pageOffsets: List<Int> = TankPageMath.pageOffsets(members.map { it.pagecount })

        val totalPages: Int = pageOffsets.last()

        val memberCount: Int = members.size

        /** The tank's own rating (its `rating:` tag), never derived from members. */
        val rating: Float = parseRatingFromTags(tags)

        /**
         * The tank's own tags grouped by namespace for the chips and the
         * edit dialog — ALL of them, rating included: the dialog PUTs the
         * whole string back, so hiding a namespace here would drop it on save.
         */
        val tagGroups: List<TagGroup> = parseLrrTagString(tags).map { (ns, values) -> TagGroup(ns, values) }
    }

    /** Snapshot of a downloaded tank used when the server is unreachable. */
    data class OfflineTank(val name: String, val members: List<Archive>)

    /** Load lifecycle; [state] is only non-null in [Loaded]. */
    sealed interface LoadState {
        data object Idle : LoadState
        data object Loading : LoadState
        data object Loaded : LoadState

        /** The source server lacks the 0.9.8 tankoubon routes and nothing is downloaded. */
        data object Unsupported : LoadState
        data class Error(val message: String) : LoadState
    }

    // -------------------------------------------------------------------------
    // Identity (set once by init)
    // -------------------------------------------------------------------------

    /** LANraragi tank id (TANK_-prefixed); set once by [init]. */
    var tankId: String = ""
        private set

    /** Source profile that owns this tank; set once by [init]. */
    var profileId: Long = 0L
        private set

    /** Name from the nav arg, for an instant toolbar title before [load] lands. */
    var seedName: String = ""
        private set

    private var initialized = false

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private val _state = MutableStateFlow<TankDetailState?>(null)

    /** Loaded tank; null until the first successful (online or offline) load. */
    val state: StateFlow<TankDetailState?> = _state.asStateFlow()

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _favoriteState = MutableStateFlow<FavoriteState?>(null)

    /**
     * Static-category membership of the TANK id (null = unknown / not yet
     * resolved / offline). Dynamic categories are ignored: they match
     * server-side and cannot be toggled.
     */
    val favoriteState: StateFlow<FavoriteState?> = _favoriteState.asStateFlow()

    private var loadJob: Job? = null

    private val _ratingError = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A rating PUT failed (already rolled back); carries the user-facing message. */
    val ratingError: SharedFlow<String> = _ratingError.asSharedFlow()

    private var ratingJob: Job? = null

    /** One-shot outcomes of the overflow operations. */
    sealed interface Event {
        /** The tank was deleted server-side; the scene closes itself. */
        data object Deleted : Event
        data class Error(val message: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // -------------------------------------------------------------------------
    // Seams (production defaults; replaceable for tests)
    // -------------------------------------------------------------------------

    internal var baseUrlResolver: suspend (Long) -> String = { id ->
        resolveSourceBaseUrl(id, ServiceRegistry.dataModule.profileLookupCache)
    }

    /**
     * Offline fallback: the downloaded-tank group of ([tankId], [profileId])
     * with its members rebuilt from the member rows' `archive_json`
     * snapshots, in group order; null when the tank is not downloaded.
     */
    internal var offlineSource: suspend (tankId: String, profileId: Long) -> OfflineTank? = { id, pid ->
        val data = ServiceRegistry.dataModule
        val group = data.downloadDbRepository.getTankGroup(id)
        if (group == null || group.serverProfileId != pid) {
            null
        } else {
            val members = TankGroupReconciler.decode(group.memberIdsJson)
                .mapNotNull { arcid -> data.historyRepository.getArchiveSnapshot(arcid, pid) }
            OfflineTank(group.name, members)
        }
    }

    /** Membership follow seam (spec 2026-09-21 §1/§3), fed after a successful online load. */
    internal var membershipSync: TankMembershipSyncFactory.Runner = TankMembershipSyncFactory.runnerSafely()

    /** First-open auto-fill seam (spec 2026-09-22 §4.5): returns the written tag string, or null. */
    internal var autoFill: suspend (OkHttpClient, String, LRRTankoubonApi.TankoubonFull) -> String? =
        { client, url, full -> TankTagSyncer.autoFillIfNeeded(client, url, full) }

    /** 「重置为成员并集」 seam (spec 2026-09-22 §5.4). */
    internal var resetTagsOp: suspend (OkHttpClient, String, String) -> Boolean =
        { client, url, id -> TankTagSyncer.reset(client, url, id) }

    /** Static-category promotion seams (spec 2026-09-22 §6): reset and dissolve. */
    internal var resetCategoriesOp: suspend (OkHttpClient, String, String, String, List<String>) -> Boolean =
        { client, url, id, name, members -> TankCategorySyncer.reset(client, url, id, name, members) }
    internal var categoryDissolve: suspend (OkHttpClient, String, String, String) -> Boolean =
        { client, url, id, name -> TankCategorySyncer.onDissolve(client, url, id, name) }

    /** After a delete: forget the remembered cover choice (spec 2026-09-22-tank-cover §4). */
    internal var forgetCoverChoice: (String) -> Unit = { TankCoverChoiceStore.default.remove(it) }

    /** After a delete: dissolve the downloaded-tank grouping; member downloads survive standalone. */
    internal var dissolveDownloadGroup: (String) -> Unit = {
        ServiceRegistry.dataModule.downloadManager.dissolveTankGroupAsync(it)
    }

    // -------------------------------------------------------------------------
    // API
    // -------------------------------------------------------------------------

    /**
     * Applies the nav args. Idempotent: only the FIRST call wins, so a view
     * recreation over a retained ViewModel keeps the loaded state intact.
     */
    fun init(tankId: String, name: String, profileId: Long) {
        if (initialized) return
        initialized = true
        this.tankId = tankId
        this.profileId = profileId
        this.seedName = name
    }

    /**
     * Fetches the tank; a repeated call cancels the in-flight one
     * (last-write-wins). Online success publishes [LoadState.Loaded] with an
     * online [TankDetailState] and then resolves the category heart; any
     * fetch failure tries [offlineSource] first and only reports
     * [LoadState.Unsupported] (404, gate flipped) or [LoadState.Error] when
     * nothing is downloaded.
     */
    fun load() {
        _loadState.value = LoadState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            var url: String? = null
            try {
                url = baseUrlResolver(profileId)
                val client = ServiceRegistry.networkModule.okHttpClient
                val online = fetchOnline(client, url)
                _state.value = online
                _loadState.value = LoadState.Loaded
                syncMembership(url, online)
                resolveFavorite(client, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val unsupported = url != null && TankoubonSupportGate.markFrom(url, e)
                val snapshot = loadOfflineQuietly()
                if (snapshot != null && snapshot.members.isNotEmpty()) {
                    _favoriteState.value = null
                    _state.value = TankDetailState(
                        tankId = tankId,
                        profileId = profileId,
                        baseUrl = url,
                        name = snapshot.name,
                        summary = null,
                        tags = "",
                        members = snapshot.members,
                        progress = 0,
                        offline = true,
                        coverFallbackMember = null,
                    )
                    _loadState.value = LoadState.Loaded
                } else if (unsupported) {
                    _loadState.value = LoadState.Unsupported
                } else {
                    Log.e(TAG, "Failed to load tankoubon detail", e)
                    val ctx = ServiceRegistry.appModule.getContext()
                    _loadState.value = LoadState.Error(friendlyError(ctx, e))
                }
            }
        }
    }

    /**
     * Applies the outcome of the category dialog (spec 2026-09-22 §4.3) to
     * the heart without waiting for the next `/api/categories` round trip.
     */
    fun updateFavoriteState(state: FavoriteState) {
        _favoriteState.value = state
    }

    /**
     * Sets the tank's OWN rating (spec 2026-09-22 §4.2): the `rating:` slot
     * of the tank tag string is replaced via [mergeRatingIntoTags] and the
     * whole string PUT as `metadata.tags`. Optimistic — [state] shows the
     * new rating at once — with rollback to the previous tag string plus a
     * [ratingError] on failure. Ignored offline, and a no-op when the value
     * already matches (saves the round trip). Never touches member ratings.
     *
     * The merge base is this page's loaded tag string (kept current by the
     * page's own tag edits), not a fresh fetch: `/full` is the only GET and
     * would re-download every member's metadata per star tap.
     */
    fun submitRating(rating: Float) {
        val current = _state.value ?: return
        if (current.offline) return
        val url = current.baseUrl ?: return
        val previousTags = current.tags
        val next = rating.coerceIn(0f, MAX_STARS)
        if (current.rating.coerceAtLeast(0f) == next) return
        val merged = mergeRatingIntoTags(previousTags, next)
        _state.value = current.copy(tags = merged)
        ratingJob?.cancel()
        ratingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                LRRTankoubonApi.updateTankoubon(client, url, tankId, tags = merged)
                AppEventBus.postArchiveRatingChangedEvent(ArchiveRatingChangedEvent(tankId, next))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Tank rating PUT failed; rolling back", e)
                _state.update { s -> if (s != null && s.tags == merged) s.copy(tags = previousTags) else s }
                val ctx = ServiceRegistry.appModule.getContext()
                _ratingError.tryEmit(friendlyError(ctx, e))
            }
        }
    }

    /**
     * 「重置为成员并集」 (spec 2026-09-22 §5.4): drop every tank tag outside
     * the excluded namespaces and rewrite the members' union (rating kept),
     * then reload server truth. Ignored offline; a failed write is reported
     * by the syncer's Snackbar and the page simply reloads.
     */
    fun resetTags() {
        val current = _state.value ?: return
        val url = current.baseUrl ?: return
        if (current.offline) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                resetTagsOp(client, url, tankId)
                resetCategoriesOp(client, url, tankId, current.name, current.memberIds)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // Best-effort: the syncer already reported.
            }
            load()
        }
    }

    /**
     * Deletes the tank server-side (never its archives): drops the
     * remembered cover choice and dissolves the downloaded-tank grouping
     * (member download rows survive as standalone downloads), then emits
     * [Event.Deleted]. Ignored offline.
     */
    fun deleteTank() {
        val current = _state.value ?: return
        val url = current.baseUrl ?: return
        if (current.offline) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                // Upstream leaves the deleted id dangling in static categories
                // (spec 2026-09-22 §6): clear it first, while the tank still exists.
                categoryDissolve(client, url, tankId, current.name)
                LRRTankoubonApi.deleteTankoubon(client, url, tankId)
                forgetCoverChoice(tankId)
                dissolveDownloadGroup(tankId)
                _events.tryEmit(Event.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val ctx = ServiceRegistry.appModule.getContext()
                _events.tryEmit(Event.Error(friendlyError(ctx, e)))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private suspend fun fetchOnline(client: OkHttpClient, url: String): TankDetailState {
        val full = LRRTankoubonApi.getTankoubonFull(client, url, tankId).result
        TankoubonSupportGate.markSupported(url)
        // First-open auto-fill (spec 2026-09-22 §4.5): a legacy tank with no
        // tags of its own gets the members' union written once, silently.
        val tags = autoFill(client, url, full) ?: full.tags.orEmpty()
        // Member ORDER is `archives` (the list a reorder PUTs); full_data is
        // only the metadata lookup — never trust its order. Multi-profile red
        // line: explicit source context for the mapper.
        val byId = full.fullData.associateBy { it.arcid }
        val ordered = full.archives.mapNotNull { byId[it] } +
            full.fullData.filter { it.arcid !in full.archives }
        val members = ordered.map { it.toArchive(sourceProfileId = profileId, sourceBaseUrl = url) }
        val fallback = resolveCoverFallback(client, url, members)
        // Fresh server truth — revalidate the cover before publishing so the
        // header bind already sees the new stamp.
        TankCoverCacheStamp.bump()
        return TankDetailState(
            tankId = tankId,
            profileId = profileId,
            baseUrl = url,
            name = full.name,
            summary = full.summary,
            tags = tags,
            members = members,
            progress = full.progress,
            offline = false,
            coverFallbackMember = fallback,
        )
    }

    /**
     * Heart = membership of the TANK id in any STATIC category (dynamic
     * ones are skipped, as for archives). Failure is non-fatal: the prior
     * state is kept rather than blinking to "not favorited".
     */
    private suspend fun resolveFavorite(client: OkHttpClient, url: String) {
        try {
            val names = LRRCategoryApi.getCategories(client, url)
                .filter { !it.isDynamic() && tankId in it.archives }
                .mapNotNull { it.name }
            _favoriteState.value = if (names.isEmpty()) {
                FavoriteState(isFavorited = false, name = null)
            } else {
                FavoriteState(isFavorited = true, name = names.first())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query categories for the tank heart")
        }
    }

    private suspend fun loadOfflineQuietly(): OfflineTank? = try {
        offlineSource(tankId, profileId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Offline tank snapshot unavailable")
        null
    }

    /**
     * Probe for a generated cover; a missing one (the probe also queues
     * server-side generation) elects the first member as the stand-in.
     * Probe failure or an empty tank keep the normal tank route.
     */
    private suspend fun resolveCoverFallback(client: OkHttpClient, url: String, members: List<Archive>): Archive? {
        val first = members.firstOrNull() ?: return null
        val hasCover = try {
            LRRTankoubonApi.hasTankThumbnail(client, url, tankId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ignored: Exception) {
            true
        }
        return if (hasCover) null else first
    }

    private fun syncMembership(url: String, s: TankDetailState) {
        val truth = listOf(TankMembershipSync.TankTruth(tankId, s.name, s.memberIds, s.progress, s.totalPages))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                membershipSync.sync(profileId, url, truth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tank membership sync failed")
            }
        }
    }

    companion object {
        private const val TAG = "TankDetailViewModel"
        private const val MAX_STARS = 5f
    }
}
