package com.hippo.ehviewer.ui.scene.gallery.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.OrphanProfileException
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.lanraragi.reader.client.api.runSuspend
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.domain.buildRatingEmoji
import kotlin.math.roundToInt
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadInfoListener
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.gallery.ReaderPageCache
import com.hippo.ehviewer.gallery.ReadingProgressTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hippo.ehviewer.download.DownloadState

/**
 * ViewModel for [GalleryDetailScene]. Manages gallery detail state, gallery info,
 * loading state, and favorite status.
 *
 * The Scene observes [StateFlow] properties and updates the UI accordingly.
 * View references, dialog display, helpers, and navigation remain in the Scene.
 */
class GalleryDetailViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // State constants
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "GalleryDetailVM"
        const val STATE_INIT = -1
        const val STATE_NORMAL = 0
        const val STATE_REFRESH = 1
        const val STATE_REFRESH_HEADER = 2
        const val STATE_FAILED = 3
    }

    // -------------------------------------------------------------------------
    // Navigation arguments (set once from Bundle)
    // -------------------------------------------------------------------------

    private val _action = MutableStateFlow<String?>(null)

    /** The action that opened this scene (currently only ACTION_ARCHIVE). */
    val action: StateFlow<String?> = _action.asStateFlow()

    private val _arcid = MutableStateFlow<String?>(null)

    /** Gallery arcid populated by the action handler. */
    val arcid: StateFlow<String?> = _arcid.asStateFlow()

    // -------------------------------------------------------------------------
    // Gallery data
    // -------------------------------------------------------------------------

    private val _archive = MutableStateFlow<Archive?>(null)

    /**
     * Archive (domain model) of the navigation argument. Populated by
     * [setArchive] from `GalleryDetailScene.handleArgs`. Until the
     * detail-API response lands this is the only source for the eager
     * header bind (thumb / title); after that, [archiveDetail] is the
     * canonical truth source via [getEffectiveArchive].
     */
    val archive: StateFlow<Archive?> = _archive.asStateFlow()

    private val _archiveDetail = MutableStateFlow<ArchiveDetail?>(null)

    /** Canonical detail-page domain model. Populated by [requestGalleryDetail] or [tryLoadFromCache]. */
    val archiveDetail: StateFlow<ArchiveDetail?> = _archiveDetail.asStateFlow()

    private val _favoriteState = MutableStateFlow<FavoriteState?>(null)

    /**
     * Whether the current archive is favorited and, if so, under which slot
     * label. `null` means "favorite status not yet resolved" (e.g. detail
     * still loading or cache hit before the categories API call returns).
     */
    val favoriteState: StateFlow<FavoriteState?> = _favoriteState.asStateFlow()

    private val _currentRating = MutableStateFlow<Float?>(null)

    /**
     * The rating currently shown / submitted by the user this session. `null`
     * means "not yet known" (detail still loading). Archive is an immutable
     * `val` so the user's edits live here rather than via in-place mutation.
     *
     * onBackPressed compares this against the initial rating to decide
     * whether to fire a result Bundle.
     */
    val currentRating: StateFlow<Float?> = _currentRating.asStateFlow()

    // -------------------------------------------------------------------------
    // Loading state
    // -------------------------------------------------------------------------

    private val _state = MutableStateFlow(STATE_INIT)

    /** Current UI state: STATE_INIT, STATE_NORMAL, STATE_REFRESH, STATE_REFRESH_HEADER, or STATE_FAILED. */
    val state: StateFlow<Int> = _state.asStateFlow()

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    fun setAction(action: String?) {
        _action.value = action
    }

    fun setArcid(arcid: String?) {
        _arcid.value = arcid
    }

    fun setArchive(archive: Archive?) {
        _archive.value = archive
    }

    /**
     * Set the canonical [ArchiveDetail] truth source. Called from the
     * Scene's `onRestore` when reviving a saved-state Bundle (process
     * death recovery).
     */
    fun setArchiveDetail(detail: ArchiveDetail?) {
        _archiveDetail.value = detail
        _currentRating.value = detail?.archive?.rating
    }

    /**
     * Update the in-memory favorite indicator. Called from the LRR
     * categories lookup (in [requestGalleryDetail]) and from the heart-icon
     * dialog after a successful add/remove operation. Mirrors the new
     * value into [favoriteStateCache] so a follow-up cache-hit navigation
     * sees the updated favorite immediately.
     */
    fun updateFavoriteState(state: FavoriteState?) {
        _favoriteState.value = state
        val arcid = getEffectiveArcid()
        if (arcid != null && state != null) {
            favoriteStateCache[arcid] = state
        }
    }

    /**
     * Record the rating the user is currently looking at. Called by the
     * rating-bar touch-release handler with the integer-rounded value, and
     * by detail load with the server-side value. onBackPressed reads this
     * to detect a session-local change.
     */
    fun updateCurrentRating(rating: Float) {
        _currentRating.value = rating
    }

    /**
     * Submit a new rating to LANraragi with **optimistic UI**: the
     * ViewModel-visible state ([_currentRating], [_archiveDetail]),
     * the in-memory LRU detail cache, and the local DownloadInfo row are
     * **all** updated synchronously before the network PUT runs. If the
     * server rejects the change the writes are rolled back and an event
     * is emitted on [ratingError] for the Scene to toast and restore the
     * RatingBar position.
     *
     * Pass `rating = 0f` to clear the rating tag entirely (LRR
     * convention: no `rating:` tag = unrated).
     *
     * Successive calls cancel the previous in-flight PUT so the user can
     * keep adjusting the bar without queueing up stale writes.
     */
    fun submitRating(rating: Float) {
        val arcid = getEffectiveArcid() ?: return
        val previousRating = _currentRating.value
        val previousAd = _archiveDetail.value

        // No-op if the user lifts their finger on the same value the
        // server already has; saves a round-trip and prevents needless
        // RatingBar bouncing.
        if (previousRating != null && previousRating == rating) return

        // === Optimistic write: every SSOT immediately reflects the new
        //     rating so any subsequent navigation / cache hit observes
        //     the change before the PUT lands. ===
        _currentRating.value = rating
        if (previousAd != null && previousAd.archive.arcid == arcid) {
            val updatedAd = previousAd.copy(
                archive = previousAd.archive.copy(rating = rating)
            )
            _archiveDetail.value = updatedAd
            ServiceRegistry.dataModule.archiveDetailCache.put(arcid, updatedAd)
        }

        pendingRatingJob?.cancel()
        pendingRatingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = resolveSourceServerUrl()
                val client = ServiceRegistry.networkModule.okHttpClient

                // Fetch latest tags so we don't clobber edits the user
                // made through other channels (tag editor, another client)
                val serverArchive = runSuspend {
                    LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcid)
                }
                val updatedTags = mergeRatingIntoTags(serverArchive.tags, rating)

                runSuspend {
                    LRRArchiveApi.updateArchiveMetadata(client, serverUrl, arcid, updatedTags)
                }

                // Persist to local DownloadInfo if the archive is in the
                // download list — keeps the Downloads page rating in sync
                // without requiring a manual refresh.
                syncRatingToDownloadInfo(arcid, rating)
                android.util.Log.d(TAG, "Rating saved: $rating for $arcid")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e(TAG, "Rating PUT failed; rolling back", e)
                withContext(Dispatchers.Main) {
                    // Roll back every SSOT we touched.
                    _currentRating.value = previousRating
                    if (previousAd != null) {
                        _archiveDetail.value = previousAd
                        ServiceRegistry.dataModule.archiveDetailCache.put(arcid, previousAd)
                    }
                    _ratingError.tryEmit(e)
                }
            }
        }
    }

    /**
     * Build the LANraragi tag string with the rating slot replaced. A
     * [rating] of 0 strips the rating tag entirely (LRR semantic for
     * "unrated"); any positive value writes `rating:⭐⭐⭐...`.
     */
    private fun mergeRatingIntoTags(originalTags: String?, rating: Float): String {
        val cleaned = (originalTags ?: "")
            .replace(Regex(",\\s*rating:[^,]*"), "")
            .replace(Regex("rating:[^,]*\\s*,?\\s*"), "")
            .trim()
            .replace(Regex("^,\\s*|,\\s*$"), "")
            .trim()
        if (rating <= 0f) return cleaned
        val ratingTag = "rating:" + buildRatingEmoji(rating.roundToInt())
        return if (cleaned.isEmpty()) ratingTag else "$cleaned, $ratingTag"
    }

    /**
     * Mirror the new rating into the in-memory + Room download row, if
     * present. The download list is the secondary surface for ratings;
     * keeping it consistent without a manual refresh is the whole point
     * of the optimistic-UI strategy.
     */
    private suspend fun syncRatingToDownloadInfo(arcid: String, rating: Float) {
        val dm = ServiceRegistry.dataModule.downloadManager
        val info = withContext(Dispatchers.Main) {
            dm.allDownloadInfoList.firstOrNull { it.arcid == arcid }
        } ?: return
        info.rating = rating
        try {
            ServiceRegistry.dataModule.downloadDbRepository.putDownloadInfo(info)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to persist rating to download DB", e)
        }
    }

    fun setState(state: Int) {
        _state.value = state
    }

    /**
     * Clear every per-entry state flow so a fresh navigation does not inherit
     * the previous gallery's data.
     *
     * Why this exists: this ViewModel is scoped via
     * `ViewModelProvider(requireActivity())`, so the same instance is reused
     * across `GalleryDetailScene` navigations. The `getEffective*()` accessors
     * fall back as `archiveDetail > archive > args`. Without an explicit
     * reset, the previously loaded `_archiveDetail` shadows the newly
     * written `_archive` and every effective arcid returns the stale
     * gallery — the new detail page renders the old gallery, downloads its
     * file, etc. The reader path is unaffected because it goes through an
     * Intent with the Archive embedded directly, bypassing the ViewModel.
     *
     * Must be called by `GalleryDetailScene.handleArgs()` before writing the
     * new arguments to the flows.
     */
    fun resetForNewEntry() {
        detailPreloadJob?.cancel()
        detailPreloadJob = null
        requestJob?.cancel()
        requestJob = null
        pendingRatingJob?.cancel()
        pendingRatingJob = null
        _action.value = null
        _arcid.value = null
        _archive.value = null
        _archiveDetail.value = null
        _favoriteState.value = null
        _currentRating.value = null
        _downloadState.value = DownloadState.INVALID
        _state.value = STATE_INIT
    }

    // -------------------------------------------------------------------------
    // Derived accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the effective arcid, preferring archiveDetail > archive >
     * arcid argument.
     */
    fun getEffectiveArcid(): String? {
        return _archiveDetail.value?.archive?.arcid
            ?: _archive.value?.arcid
            ?: _arcid.value
    }

    /**
     * Returns the best available [Archive] for display. Prefers the rich
     * detail when loaded (now sourced directly from `_archiveDetail`),
     * otherwise the navigation argument [_archive].
     */
    fun getEffectiveArchive(): Archive? {
        return _archiveDetail.value?.archive ?: _archive.value
    }

    /**
     * Resolve the LANraragi base URL that owns this archive. Routes by
     * the archive's `serverProfileId` so a download originally fetched
     * from server B is contacted at server B even when the user has
     * since switched the active profile to server A.
     *
     * @throws OrphanProfileException when the source profile no longer
     *         exists (deleted) and there is no active-profile fallback.
     */
    private suspend fun resolveSourceServerUrl(): String {
        val sid = getEffectiveArchive()?.serverProfileId ?: 0L
        val cache = ServiceRegistry.dataModule.profileLookupCache
        return resolveSourceBaseUrl(sid, cache)
    }

    // -------------------------------------------------------------------------
    // Local reading progress (reactive)
    //
    // Tracks the latest 0-indexed page persisted by the reader for the current
    // gallery's arcid. Re-emits whenever the reader writes to local storage,
    // so the detail UI sees fresh progress as soon as the user returns from
    // the reader instead of relying on a one-shot onResume re-read.
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    val localReadingPage: StateFlow<Int> = combine(
        _archiveDetail, _archive, _arcid
    ) { ad, archive, argArcid ->
        ad?.archive?.arcid ?: archive?.arcid ?: argArcid
    }
        .distinctUntilChanged()
        .flatMapLatest { arcid ->
            if (arcid.isNullOrEmpty()) flowOf(ReadingProgressTracker.NO_LOCAL_PROGRESS)
            else ReadingProgressTracker.progressFlow(arcid)
        }
        // Lazily started so the flow does not run (and does not touch
        // ServiceRegistry / SharedPreferences) until the Scene subscribes.
        // Avoids needless work on Activity-scoped VM construction and keeps
        // unit tests (which never collect) free of Android dependencies.
        .stateIn(viewModelScope, SharingStarted.Lazily, ReadingProgressTracker.NO_LOCAL_PROGRESS)

    // -------------------------------------------------------------------------
    // Detail-page reading preload
    // -------------------------------------------------------------------------

    /** Background job that preloads reading pages from the detail page. */
    private var detailPreloadJob: Job? = null

    /**
     * Warm up the reader before the user taps "open". For downloaded
     * archives we list the local directory and decode the start page
     * straight into the [ReaderPageCache] decoded slot. For LRR-only
     * archives we fall back to [ReaderPageCache.preloadForDetail],
     * which downloads bytes and decode-warms the same slot.
     *
     * Either way, [DirGalleryProvider]/[com.hippo.ehviewer.gallery.LRRGalleryProvider]
     * gets a chance to skip the first-page decode on next reader open.
     */
    private fun triggerReadingPreload(arcId: String, serverProgress: Int) {
        detailPreloadJob?.cancel()
        val context = ServiceRegistry.appModule.getContext()
        val archive = _detailLoaded.replayCache.firstOrNull()?.archive ?: return

        val localProgress = GalleryProvider2.loadReadingProgress(context, arcId)
        val startPage = when {
            serverProgress > 0 && localProgress > 0 ->
                maxOf(serverProgress - 1, localProgress) // server is 1-indexed
            serverProgress > 0 -> serverProgress - 1
            localProgress > 0 -> localProgress
            else -> 0
        }

        detailPreloadJob = viewModelScope.launch {
            // Resolve the local download directory (DB lookup) off the
            // main thread before deciding which warmup path to use.
            val dir = withContext(Dispatchers.IO) {
                com.hippo.ehviewer.ui.GalleryOpenHelper
                    .getLocalDownloadDir(context, archive)
            }
            if (dir != null) {
                val uniFile = com.hippo.unifile.UniFile.fromFile(dir)
                if (uniFile != null) {
                    android.util.Log.i(
                        TAG,
                        "[WARM] detailVM DIR trigger arcid=$arcId page=$startPage"
                    )
                    ReaderPageCache.warmDir(context, arcId, uniFile).join()
                    return@launch
                }
            }
            val serverUrl = try {
                resolveSourceServerUrl()
            } catch (_: OrphanProfileException) {
                // Source profile is gone — preloading from network is
                // impossible. The reader will surface a localised tip
                // when the user actually opens the gallery.
                return@launch
            }
            android.util.Log.i(
                TAG,
                "[WARM] detailVM LRR trigger arcid=$arcId page=$startPage"
            )
            ReaderPageCache.preloadForDetail(context, arcId, serverUrl, startPage).join()
        }
    }

    // -------------------------------------------------------------------------
    // Detail request events (one-shot)
    // -------------------------------------------------------------------------

    private val _detailError = MutableSharedFlow<Exception>(extraBufferCapacity = 1)

    /** Emitted once when a gallery detail fetch fails. Observe to show error UI. */
    val detailError: SharedFlow<Exception> = _detailError.asSharedFlow()

    private val _detailLoaded = MutableSharedFlow<ArchiveDetail>(extraBufferCapacity = 1)

    /**
     * Emitted every time a fresh detail-page response lands successfully.
     * Independent of [archiveDetail] — that StateFlow deduplicates by
     * value, so a refresh that returns identical content would not re-fire
     * `collect`. Observers that drive REFRESH→NORMAL state transitions
     * (the spinner) must use this event flow instead.
     */
    val detailLoaded: SharedFlow<ArchiveDetail> = _detailLoaded.asSharedFlow()

    private val _ratingError = MutableSharedFlow<Exception>(extraBufferCapacity = 1)

    /**
     * Emitted when an in-flight rating PUT fails after the optimistic UI
     * write. The Scene listens to roll back the visible rating and toast
     * the user.
     */
    val ratingError: SharedFlow<Exception> = _ratingError.asSharedFlow()

    /** Cancels the previous detail fetch when refresh is invoked twice in a row. */
    private var requestJob: Job? = null

    /** Cancels the previous in-flight rating PUT if the user keeps adjusting the bar. */
    private var pendingRatingJob: Job? = null

    /**
     * Per-arcid favorite-state cache so that cache-hit detail navigations
     * (LRU `archiveDetailCache` already remembered the metadata) restore
     * the heart icon instead of momentarily showing "no category" until
     * the next categories request lands.
     */
    private val favoriteStateCache = mutableMapOf<String, FavoriteState>()

    // -------------------------------------------------------------------------
    // Download state tracking
    // -------------------------------------------------------------------------

    private val _downloadState = MutableStateFlow(DownloadState.INVALID)

    /** Current download state for the displayed gallery. */
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /**
     * Initialize download state for the given arcid.
     */
    fun initDownloadState(arcid: String?) {
        _downloadState.value = if (!arcid.isNullOrEmpty()) {
            downloadManager.getDownloadState(arcid)
        } else {
            DownloadState.INVALID
        }
    }

    /**
     * Re-query the current download state from [DownloadManager].
     */
    fun refreshDownloadState() {
        val arcid = getEffectiveArcid()
        if (arcid.isNullOrEmpty()) return
        _downloadState.value = downloadManager.getDownloadState(arcid)
    }

    /** [DownloadInfoListener] that updates [_downloadState] on any change. */
    val downloadInfoListener: DownloadInfoListener = object : DownloadInfoListener {
        override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
            refreshDownloadState()
        }
        override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {}
        override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>, mWaitList: List<DownloadInfo>) {
            refreshDownloadState()
        }
        override fun onUpdateAll() { refreshDownloadState() }
        override fun onReload() { refreshDownloadState() }
        override fun onChange() { refreshDownloadState() }
        override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
            refreshDownloadState()
        }
        override fun onRenameLabel(from: String, to: String) {}
        override fun onUpdateLabels() {}
    }

    // -------------------------------------------------------------------------
    // Service accessors (read-through to ServiceRegistry so the Scene does not
    // need to import ServiceRegistry directly)
    // -------------------------------------------------------------------------

    /** The app's [DownloadManager] singleton. */
    val downloadManager: DownloadManager
        get() = ServiceRegistry.dataModule.downloadManager

    // -------------------------------------------------------------------------
    // Data operations (all dispatched on viewModelScope so they outlive the Scene)
    // -------------------------------------------------------------------------

    /**
     * Records [archive] in the history table. Fire-and-forget; runs on
     * [Dispatchers.IO].
     */
    fun recordHistory(archive: Archive) {
        viewModelScope.launch(Dispatchers.IO) {
            ServiceRegistry.dataModule.historyRepository.putHistoryInfo(archive)
        }
    }

    /**
     * Persists [info] to the downloads table. Fire-and-forget; runs on [Dispatchers.IO].
     */
    fun persistDownloadInfo(info: DownloadInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            ServiceRegistry.dataModule.downloadDbRepository.putDownloadInfo(info)
        }
    }

    /**
     * Suspends to check whether [arcid] is in the local favorites table.
     * Runs on [Dispatchers.IO].
     */
    suspend fun isLocalFavorite(arcid: String?): Boolean = withContext(Dispatchers.IO) {
        if (arcid != null) {
            ServiceRegistry.dataModule.favoritesRepository.containsLocalFavorite(arcid)
        } else {
            false
        }
    }

    // -------------------------------------------------------------------------
    // Detail request (LRR metadata fetch + category favorite detection)
    // -------------------------------------------------------------------------

    /**
     * Fetches archive metadata from LANraragi and queries categories for
     * favorite status. On success, updates [_archiveDetail],
     * [_currentRating] and [_favoriteState]; emits to [_detailLoaded].
     * On failure, emits to [_detailError].
     *
     * Subsequent calls cancel the in-flight job — the user should always
     * see "last-write-wins" behaviour even when tapping refresh
     * repeatedly. Without this, multiple parallel coroutines would race
     * to write `_favoriteState` and the user can lose their categories
     * to a stale response.
     *
     * @param categoryInfoSuffix localized string for " etc." suffix
     * @param categoryCountSuffix localized string for " categories" suffix
     * @return true if the request was dispatched, false if prerequisites are missing
     */
    fun requestGalleryDetail(
        categoryInfoSuffix: String,
        categoryCountSuffix: String
    ): Boolean {
        val arcid = getEffectiveArcid()
        if (arcid.isNullOrEmpty()) {
            return false
        }

        val client = ServiceRegistry.networkModule.okHttpClient

        // Cancel any previous in-flight fetch so its late response cannot
        // overwrite the freshly issued one's result.
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Resolve inside the coroutine: ProfileLookupCache may be
                // cold on first launch and resolveSourceBaseUrl suspends
                // until it hydrates. OrphanProfileException is surfaced
                // through _detailError just like any other fetch failure.
                val serverUrl = resolveSourceServerUrl()
                val archive = runSuspend {
                    LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcid)
                }
                val ad = archive.toArchiveDetail()

                // Query LANraragi categories to determine favorite status.
                // Failure here is non-fatal — keep the previously known
                // favorite state rather than blanking it.
                try {
                    val categories = runSuspend {
                        LRRCategoryApi.getCategories(client, serverUrl)
                    }
                    val matchedNames = mutableListOf<String>()
                    for (cat in categories) {
                        if (!cat.isDynamic() && cat.archives.contains(arcid)) {
                            cat.name?.let { matchedNames.add(it) }
                        }
                    }
                    val newState = if (matchedNames.isNotEmpty()) {
                        val displayName = if (matchedNames.size == 1) {
                            matchedNames[0]
                        } else {
                            matchedNames[0] +
                                categoryInfoSuffix +
                                matchedNames.size +
                                categoryCountSuffix
                        }
                        FavoriteState(isFavorited = true, name = displayName)
                    } else {
                        FavoriteState(isFavorited = false, name = null)
                    }
                    favoriteStateCache[arcid] = newState
                    _favoriteState.value = newState
                } catch (catEx: Exception) {
                    android.util.Log.w(
                        TAG,
                        "Failed to query categories for favorite status",
                        catEx
                    )
                    // Keep the prior favorite state — better to show stale
                    // than to blink to "not favorited".
                }

                // Cache the detail
                ServiceRegistry.dataModule.archiveDetailCache.put(arcid, ad)

                _archiveDetail.value = ad
                _currentRating.value = ad.archive.rating
                // Fire detailLoaded *after* the StateFlow write so any
                // observer that snapshots archiveDetail.value sees the
                // fresh content. Distinct from archiveDetail itself —
                // refresh-with-no-change still fires this event so the
                // REFRESH→NORMAL spinner transition can complete.
                _detailLoaded.tryEmit(ad)

                // Preload reading pages in background
                triggerReadingPreload(arcid, archive.progress)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e(TAG, "LRR metadata fetch failed", e)
                _detailError.tryEmit(e)
            }
        }
        return true
    }

    // -------------------------------------------------------------------------
    // Cache lookup
    // -------------------------------------------------------------------------

    /**
     * Attempts to load gallery detail from cache if not already present.
     * Returns true if data is available or a request should be made,
     * false if the gid is invalid.
     */
    fun tryLoadFromCache(): Boolean {
        if (_archiveDetail.value != null) return true

        val arcid = getEffectiveArcid() ?: return false

        val cached = ServiceRegistry.dataModule.archiveDetailCache.get(arcid)
        if (cached != null) {
            _archiveDetail.value = cached
            _currentRating.value = cached.archive.rating
            // Restore the heart-icon state if we resolved it on a prior
            // visit; otherwise the binder briefly shows "not favorited"
            // until the next requestGalleryDetail finishes.
            favoriteStateCache[arcid]?.let { _favoriteState.value = it }
            return true
        }
        return true
    }

}

/**
 * Detail-page favorite indicator. Lives on
 * [GalleryDetailViewModel.favoriteState] as `null` (unresolved /
 * unknown), `FavoriteState(false, null)` (resolved → not favorited),
 * or `FavoriteState(true, displayName)` (resolved → favorited under
 * `displayName`).
 *
 * The display name composes the matched LRR category(ies) — see
 * `requestGalleryDetail` for the formatting.
 */
data class FavoriteState(
    val isFavorited: Boolean,
    val name: String?,
)
