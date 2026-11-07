package com.lanraragi.reader.ui.scene.gallery.detail

import com.lanraragi.reader.archive.ArchiveDetailLoader
import com.lanraragi.reader.archive.ArchiveRatingWriter
import com.lanraragi.reader.domain.Tankoubon
import com.lanraragi.reader.tankoubon.TankRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.dao.toDegradedArchiveDetail
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.server.ServerConnections
import com.lanraragi.reader.server.TankoubonSupportGate
import com.lanraragi.reader.server.OrphanProfileException
import com.lanraragi.reader.server.probeSourceHealthy
import com.lanraragi.reader.server.resolveSourceBaseUrl
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.download.DownloadInfoListener
import com.lanraragi.reader.download.DownloadManager
import com.lanraragi.reader.reading.LocalReadingProgress
import com.lanraragi.reader.reading.ReaderPageCache
import com.lanraragi.reader.reading.ReadingProgressReconciler
import com.lanraragi.reader.reading.ReadingProgressTracker
import com.lanraragi.reader.ui.TankMembershipSyncFactory
import com.lanraragi.reader.util.suspendRunCatching
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
import com.lanraragi.reader.dao.DownloadState
import com.lanraragi.reader.domain.FavoriteState

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
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
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

    private val _archiveTankoubons = MutableStateFlow<List<Tankoubon>?>(null)

    /**
     * Tankoubons on the source server that contain this archive. `null` =
     * unknown (not loaded yet, pre-0.9.8 server, lookup failure) and hides
     * the section; an EMPTY list means the server answered and the archive
     * is in no tank, which still shows the section so its Edit entry can
     * add the archive to one (spec 2026-09-22 §6).
     */
    val archiveTankoubons: StateFlow<List<Tankoubon>?> = _archiveTankoubons.asStateFlow()

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
            favoriteStateCache.put(arcid, state)
        }
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

        // The write runs in ArchiveRatingWriter's process scope, keyed by
        // arcid: leaving this entry never cancels it (the optimistic cache
        // write above is already visible), and only a newer rating of the
        // SAME archive supersedes a pending PUT.
        ratingWriter.submit(getSourceProfileId(), arcid, rating) { e ->
            withContext(Dispatchers.Main) {
                // Roll back every SSOT we touched: the shared cache
                // always, this screen's state only if it still shows
                // the same archive.
                if (previousAd != null) {
                    ServiceRegistry.dataModule.archiveDetailCache.put(arcid, previousAd)
                }
                if (getEffectiveArcid() == arcid) {
                    _currentRating.value = previousRating
                    if (previousAd != null) _archiveDetail.value = previousAd
                    _ratingError.tryEmit(e)
                }
            }
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
        detailRequestEvents.clear()
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
     * The archive's true source profile id, sourced from the navigation
     * argument (which the Scene populates from Room's
     * ARCHIVE_LOCAL_STATE — the canonical persistent record). We
     * deliberately avoid [getEffectiveArchive] here because that
     * accessor prefers `_archiveDetail`, which can be hydrated from
     * the in-memory LRU detail cache. A 1.12.7-era cache entry would
     * carry a stale `serverProfileId = active profile` (regression
     * fixed in 1.12.8), so falling through to detail's snapshot would
     * keep routing the user back to the wrong server even after the
     * underlying mapper bug was fixed.
     */
    fun getSourceProfileId(): Long = _archive.value?.serverProfileId ?: 0L

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
        val cache = ServiceRegistry.dataModule.profileLookupCache
        return resolveSourceBaseUrl(getSourceProfileId(), cache)
    }

    /** The reverse lookup (it never calls [TankRepository.loadAll]). */
    private val tanks by lazy { TankRepository(TankMembershipSyncFactory.runnerSafely()::sync) }

    /**
     * Reverse-lookup the tankoubons containing this archive, source-profile
     * routed. Silent by design: UNSUPPORTED/404/any failure leaves the list
     * empty so the detail row simply hides — pre-0.9.8 servers must not
     * error-spam the detail page.
     */
    fun loadArchiveTankoubons() {
        val arcid = getEffectiveArcid() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conn = ServerConnections.forProfile(getSourceProfileId())
                if (TankoubonSupportGate.isUnsupported(conn.baseUrl)) return@launch
                _archiveTankoubons.value = tanks.tanksContaining(conn, arcid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                suspendRunCatching { resolveSourceServerUrl() }.getOrNull()
                    ?.let { TankoubonSupportGate.markFrom(it, e) }
                _archiveTankoubons.value = null
            }
        }
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
        // Source profile from the nav arg, as in [getSourceProfileId]: the
        // reader saves under it, so a download of another server must show
        // its page here too (audit 2026-10-06c C37 note).
        (ad?.archive?.arcid ?: archive?.arcid ?: argArcid) to (archive?.serverProfileId ?: 0L)
    }
        .distinctUntilChanged()
        .flatMapLatest { (arcid, serverProfileId) ->
            if (arcid.isNullOrEmpty()) {
                flowOf(ReadingProgressTracker.NO_LOCAL_PROGRESS)
            } else {
                ReadingProgressTracker.progressFlow(LocalReadingProgress.sourceProfile(serverProfileId), arcid)
            }
        }
        // Lazily started so the flow does not run (and does not touch
        // ServiceRegistry / SharedPreferences) until the Scene subscribes.
        // Avoids needless work on Activity-scoped VM construction and keeps
        // unit tests (which never collect) free of Android dependencies.
        .stateIn(viewModelScope, SharingStarted.Lazily, ReadingProgressTracker.NO_LOCAL_PROGRESS)

    // -------------------------------------------------------------------------
    // Detail-page reading preload
    // -------------------------------------------------------------------------

    /**
     * Background job that preloads reading pages from the detail page. It
     * owns the app-scope warm it awaits ([ReaderPageCache.joinOwned]), so
     * cancelling it stops the server extraction, downloads and decode too.
     */
    @Volatile
    private var detailPreloadJob: Job? = null

    /**
     * Set when the page is left, cleared by the next [requestGalleryDetail]:
     * a fetch still in flight when the user left must not start a preload.
     */
    @Volatile
    private var readingPreloadSuppressed = false

    /** The user left the detail page: stop its reading preload (audit C18). */
    fun cancelReadingPreload() {
        readingPreloadSuppressed = true
        detailPreloadJob?.cancel()
        detailPreloadJob = null
    }

    /**
     * Warm up the reader before the user taps "open". For downloaded
     * archives we list the local directory and decode the start page
     * straight into the [ReaderPageCache] decoded slot. For LRR-only
     * archives we fall back to [ReaderPageCache.preloadForDetail],
     * which downloads bytes and decode-warms the same slot.
     *
     * Either way, [DirGalleryProvider]/[com.lanraragi.reader.reading.LRRGalleryProvider]
     * gets a chance to skip the first-page decode on next reader open.
     *
     * [archive] is the freshly fetched detail's archive, passed in: the
     * trigger used to read the load event's replay cache, which was always
     * empty (replay 0 then), so the preload never ran (audit C18 ruling R9).
     */
    private fun triggerReadingPreload(arcId: String, archive: Archive) {
        detailPreloadJob?.cancel()
        if (readingPreloadSuppressed) return
        val context = ServiceRegistry.appModule.getContext()

        // Same offline-reconcile math as GalleryOpenHelper and the Dir seed
        // (the old timestamp-blind maxOf heuristic could warm a page the
        // reader's reconciler would never resolve to). `archive` here is the
        // freshly-fetched detail metadata, so this also matches the restore
        // flow's own fresh reconcile — the provider's post-resolve consume
        // picks the slot up even when SP and snapshot disagree.
        val startPage = ReadingProgressReconciler.resolveOffline(
            context, arcId, archive.progress, archive.lastreadtime, archive.serverProfileId
        )

        detailPreloadJob = viewModelScope.launch {
            // Resolve the local download directory (DB lookup) off the
            // main thread before deciding which warmup path to use.
            val dir = withContext(Dispatchers.IO) {
                com.lanraragi.reader.ui.GalleryOpenHelper
                    .getLocalDownloadDir(context, archive)
            }
            if (dir != null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        TAG,
                        "[WARM] detailVM DIR trigger arcid=$arcId page=$startPage"
                    )
                }
                val storage = com.lanraragi.reader.storage.StorageFile(dir)
                ReaderPageCache.joinOwned(ReaderPageCache.warmDir(context, arcId, storage, startPage))
                return@launch
            }
            val serverUrl = try {
                resolveSourceServerUrl()
            } catch (_: OrphanProfileException) {
                // Source profile is gone — preloading from network is
                // impossible. The reader will surface a localised tip
                // when the user actually opens the gallery.
                return@launch
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.i(
                    TAG,
                    "[WARM] detailVM LRR trigger arcid=$arcId page=$startPage"
                )
            }
            ReaderPageCache.joinOwned(ReaderPageCache.preloadForDetail(context, arcId, serverUrl, startPage))
        }
    }

    // -------------------------------------------------------------------------
    // Detail request events (one-shot)
    // -------------------------------------------------------------------------

    internal val detailRequestEvents = DetailRequestEvents()

    /**
     * Emitted when a gallery detail fetch fails AND there is no usable
     * fallback content to render — the Scene shows the full-screen
     * `mTip` error pane. Kept for a late subscriber ([DetailRequestEvents]).
     */
    val detailError: SharedFlow<Exception> = detailRequestEvents.failed

    /**
     * Emitted when a gallery detail fetch fails BUT we already have
     * displayable data (LRU detail cache hit, or a degraded detail
     * seeded from the navigation [archive] argument). The Scene leaves
     * the main content visible and surfaces a non-blocking banner so
     * the page stays useful when the source server is offline / orphan
     * / has deleted the archive.
     */
    val detailErrorBanner: SharedFlow<Exception> = detailRequestEvents.failedWithContent

    /**
     * Emitted every time a fresh detail-page response lands successfully.
     * Independent of [archiveDetail] — that StateFlow deduplicates by
     * value, so a refresh that returns identical content would not re-fire
     * `collect`. Observers that drive REFRESH→NORMAL state transitions
     * (the spinner) must use this event flow instead. Kept for a late
     * subscriber: the request can finish before the view's collectors
     * subscribe ([DetailRequestEvents]).
     */
    val detailLoaded: SharedFlow<ArchiveDetail> = detailRequestEvents.loaded

    private val _ratingError = MutableSharedFlow<Exception>(extraBufferCapacity = 1)

    /**
     * Emitted when an in-flight rating PUT fails after the optimistic UI
     * write. The Scene listens to roll back the visible rating and toast
     * the user.
     */
    val ratingError: SharedFlow<Exception> = _ratingError.asSharedFlow()

    /** Cancels the previous detail fetch when refresh is invoked twice in a row. */
    private var requestJob: Job? = null

    /** Writes ratings to the server; the app's process-wide instance unless a test swaps it. */
    internal var ratingWriter: ArchiveRatingWriter = ArchiveRatingWriter.default

    /**
     * Per-arcid favorite-state cache (process-wide, next to `archiveDetailCache`,
 * cleared with it) so that cache-hit detail navigations
     * (LRU `archiveDetailCache` already remembered the metadata) restore
     * the heart icon instead of momentarily showing "no category" until
     * the next categories request lands.
     */
    private val favoriteStateCache get() = ServiceRegistry.dataModule.archiveFavoriteCache

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
     * Copies the freshly fetched [archive] into its download row, if the
     * download still exists. Main thread: the live row is mutated there and
     * persisted through the download write queue, so it cannot be written
     * mid-transition or after a queued delete (audit 2026-10-06b STAB-01).
     */
    fun updateDownloadRowFromDetail(archive: Archive) {
        downloadManager.updateDownloadInfo(archive.arcid) { it.updateInfo(archive) }
    }

    /**
     * Suspends to check whether [arcid] is in the local favorites table.
     * Runs on [Dispatchers.IO].
     */
    suspend fun isLocalFavorite(arcid: String?): Boolean = withContext(Dispatchers.IO) {
        if (arcid != null) {
            ServiceRegistry.dataModule.favoritesRepository.containsLocalFavorite(arcid, getSourceProfileId())
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
     * [_currentRating] and [_favoriteState]; emits to [detailLoaded].
     * On failure, emits to [detailError] or [detailErrorBanner].
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
        readingPreloadSuppressed = false

        val client = ServiceRegistry.networkModule.okHttpClient

        // Cancel any previous in-flight fetch so its late response cannot
        // overwrite the freshly issued one's result.
        requestJob?.cancel()
        detailRequestEvents.clear()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Resolve inside the coroutine: ProfileLookupCache may be
                // cold on first launch and resolveSourceBaseUrl suspends
                // until it hydrates. OrphanProfileException is surfaced
                // through detailError just like any other fetch failure.
                //
                // Read the source id from `_archive` (Room nav arg), not
                // from `getEffectiveArchive` — the latter would echo a
                // 1.12.7-era polluted LRU entry back into the freshly
                // built ArchiveDetail, perpetuating the wrong sid.
                val conn = ServerConnections.forProfile(getSourceProfileId())
                val ad = ArchiveDetailLoader.detail(conn, arcid)

                // Query the categories holding this archive (per-archive
                // endpoint, not the full list) for the favourite heart.
                // Failure here is non-fatal — keep the previously known
                // favorite state rather than blanking it.
                try {
                    val categories = ArchiveDetailLoader.categories(conn, arcid)
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
                    favoriteStateCache.put(arcid, newState)
                    _favoriteState.value = newState
                } catch (catEx: Exception) {
                    if (catEx is kotlinx.coroutines.CancellationException) throw catEx
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
                detailRequestEvents.loaded(ad)

                loadArchiveTankoubons()

                // Preload reading pages in background
                triggerReadingPreload(arcid, ad.archive)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e(TAG, "LRR metadata fetch failed", e)
                emitDetailFailure(disambiguateSourceFailure(e, client))
            }
        }
        return true
    }

    /**
     * Re-classify a 4xx-flavoured fetch failure based on whether the
     * source server itself is alive. Reverse proxies in front of an
     * offline LANraragi backend often respond 400/404/502 on every
     * request — without a probe we cannot distinguish that from a
     * genuinely missing archive, so the banner can falsely promise
     * `已删除` when the user only stopped the server.
     *
     * The probe runs only on those status codes so the happy path is
     * unaffected; on any other exception type (including the
     * connect/timeout cases) we return the original unchanged.
     */
    private suspend fun disambiguateSourceFailure(
        e: Exception,
        client: okhttp3.OkHttpClient,
    ): Exception {
        if (e !is LRRHttpException) return e
        if (e.code != HTTP_BAD_REQUEST && e.code != HTTP_NOT_FOUND) return e
        val baseUrl = suspendRunCatching { resolveSourceServerUrl() }
            .getOrElse { return e }
        val healthy = probeSourceHealthy(client, baseUrl)
        return if (healthy) {
            // Server answered /api/info → the original 4xx really was
            // about this archive; keep the original exception so the
            // classifier maps it to the `已删除 / not found` banner.
            e
        } else {
            // Probe also failed → the upstream is offline (or a proxy
            // is misbehaving). Surface as a connection failure so the
            // banner reads `不可达`, matching the genuine-down case.
            java.net.ConnectException("Source server health probe failed").also {
                it.initCause(e)
            }
        }
    }

    /**
     * Route a detail-fetch failure to either the banner flow (cache-
     * first: the page already has displayable data, banner stays atop
     * content) or the full-screen flow (no usable data → big sad
     * pandroid). Falls back to seeding [_archiveDetail] from the
     * navigation [_archive] when nothing else is on hand: the local
     * Archive entity carries enough fields (title / thumb / tags) to
     * paint a useful page even if the source server is unreachable.
     */
    private fun emitDetailFailure(e: Exception) {
        if (_archiveDetail.value == null) {
            val arg = _archive.value
            if (arg != null) {
                _archiveDetail.value = arg.toDegradedArchiveDetail()
                if (_currentRating.value == null) {
                    _currentRating.value = arg.rating
                }
            }
        }
        if (_archiveDetail.value != null) {
            detailRequestEvents.failedWithContent(e)
        } else {
            detailRequestEvents.failed(e)
        }
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
            // Cache hits skip requestGalleryDetail, so the tank row needs
            // its own kick here.
            loadArchiveTankoubons()
            return true
        }
        return true
    }

}
