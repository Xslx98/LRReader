package com.hippo.ehviewer.ui.scene.gallery.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.runSuspend
import com.hippo.ehviewer.mapper.toArchiveDetail
import com.lanraragi.reader.domain.ArchiveDetail
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

    /** The action that opened this scene (ACTION_GALLERY_INFO, ACTION_GID_TOKEN, etc.). */
    val action: StateFlow<String?> = _action.asStateFlow()

    private val _gid = MutableStateFlow(0L)

    /** Gallery ID, used when action is ACTION_GID_TOKEN. */
    val gid: StateFlow<Long> = _gid.asStateFlow()

    private val _arcid = MutableStateFlow<String?>(null)

    /** Gallery arcid, used when action is ACTION_GID_TOKEN. */
    val arcid: StateFlow<String?> = _arcid.asStateFlow()

    // -------------------------------------------------------------------------
    // Gallery data
    // -------------------------------------------------------------------------

    private val _galleryInfo = MutableStateFlow<GalleryInfo?>(null)

    /** The initial gallery info passed via arguments (before detail is loaded). */
    val galleryInfo: StateFlow<GalleryInfo?> = _galleryInfo.asStateFlow()

    private val _galleryDetail = MutableStateFlow<GalleryDetail?>(null)

    /** The full gallery detail, loaded from the LANraragi API. */
    val galleryDetail: StateFlow<GalleryDetail?> = _galleryDetail.asStateFlow()

    private val _archiveDetail = MutableStateFlow<ArchiveDetail?>(null)

    /** Domain model for display. Populated alongside [galleryDetail] from the same API response. */
    val archiveDetail: StateFlow<ArchiveDetail?> = _archiveDetail.asStateFlow()

    private val _downloadInfo = MutableStateFlow<DownloadInfo?>(null)

    /** Download info when opened from downloads scene. */
    val downloadInfo: StateFlow<DownloadInfo?> = _downloadInfo.asStateFlow()

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

    fun setGid(gid: Long) {
        _gid.value = gid
    }

    fun setArcid(arcid: String?) {
        _arcid.value = arcid
    }

    fun setGalleryInfo(info: GalleryInfo?) {
        _galleryInfo.value = info
    }

    fun setGalleryDetail(detail: GalleryDetail?) {
        _galleryDetail.value = detail
    }

    fun setDownloadInfo(info: DownloadInfo?) {
        _downloadInfo.value = info
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
     * fall back as `detail > info > args`. Without an explicit reset, the
     * previously loaded `_galleryDetail` shadows the newly written
     * `_galleryInfo` and every effective gid / arcid / category returns the
     * stale gallery — the new detail page renders the old gallery, downloads
     * its file, etc. The reader path is unaffected because it goes through an
     * Intent with the GalleryInfo embedded directly, bypassing the ViewModel.
     *
     * Must be called by `GalleryDetailScene.handleArgs()` before writing the
     * new arguments to the flows.
     */
    fun resetForNewEntry() {
        detailPreloadJob?.cancel()
        detailPreloadJob = null
        _action.value = null
        _gid.value = 0L
        _arcid.value = null
        _galleryInfo.value = null
        _galleryDetail.value = null
        _archiveDetail.value = null
        _downloadInfo.value = null
        _downloadState.value = DownloadInfo.STATE_INVALID
        _state.value = STATE_INIT
    }

    // -------------------------------------------------------------------------
    // Derived accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the effective gallery ID, preferring galleryDetail > galleryInfo > gid argument.
     * Returns -1 if none is available.
     */
    fun getEffectiveGid(): Long {
        val detail = _galleryDetail.value
        if (detail != null) return detail.gid

        val info = _galleryInfo.value
        if (info != null) return info.gid

        if (GalleryDetailScene.ACTION_GID_TOKEN == _action.value) {
            return _gid.value
        }
        return -1
    }

    /**
     * Returns the effective arcid, preferring galleryDetail > galleryInfo > arcid argument.
     */
    fun getEffectiveArcid(): String? {
        val detail = _galleryDetail.value
        if (detail != null) return detail.arcid

        val info = _galleryInfo.value
        if (info != null) return info.arcid

        if (GalleryDetailScene.ACTION_GID_TOKEN == _action.value) {
            return _arcid.value
        }
        return null
    }

    /**
     * Returns the effective uploader string, preferring galleryDetail > galleryInfo.
     */
    fun getEffectiveUploader(): String? {
        return _galleryDetail.value?.uploader ?: _galleryInfo.value?.uploader
    }

    /**
     * Returns the effective category, preferring galleryDetail > galleryInfo.
     * Returns -1 if none is available.
     */
    fun getEffectiveCategory(): Int {
        val detail = _galleryDetail.value
        if (detail != null) return detail.category

        val info = _galleryInfo.value
        if (info != null) return info.category

        return -1
    }

    /**
     * Returns the best available gallery info object (detail preferred over info).
     */
    fun getEffectiveGalleryInfo(): GalleryInfo? {
        return _galleryDetail.value ?: _galleryInfo.value
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
        _galleryDetail, _galleryInfo, _arcid, _action
    ) { gd, gi, argArcid, action ->
        gd?.arcid
            ?: gi?.arcid
            ?: if (action == GalleryDetailScene.ACTION_GID_TOKEN) argArcid else null
    }
        .distinctUntilChanged()
        .flatMapLatest { arcid ->
            if (arcid.isNullOrEmpty()) flowOf(0) else ReadingProgressTracker.progressFlow(arcid)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // -------------------------------------------------------------------------
    // Detail-page reading preload
    // -------------------------------------------------------------------------

    /** Background job that preloads reading pages from the detail page. */
    private var detailPreloadJob: Job? = null

    /**
     * Preload 2 pages (at the reading progress position) into the reader's
     * cache so that opening the reader produces an immediate cache hit.
     */
    private fun triggerReadingPreload(arcId: String, serverProgress: Int) {
        detailPreloadJob?.cancel()
        val serverUrl = LRRAuthManager.getServerUrl() ?: return
        val context = ServiceRegistry.appModule.getContext()

        val localProgress = GalleryProvider2.loadReadingProgress(context, arcId)
        val startPage = when {
            serverProgress > 0 && localProgress > 0 ->
                maxOf(serverProgress - 1, localProgress) // server is 1-indexed
            serverProgress > 0 -> serverProgress - 1
            localProgress > 0 -> localProgress
            else -> 0
        }

        detailPreloadJob = ReaderPageCache.preloadForDetail(context, arcId, serverUrl, startPage)
    }

    // -------------------------------------------------------------------------
    // Detail request error (one-shot event)
    // -------------------------------------------------------------------------

    private val _detailError = MutableSharedFlow<Exception>(extraBufferCapacity = 1)

    /** Emitted once when a gallery detail fetch fails. Observe to show error UI. */
    val detailError: SharedFlow<Exception> = _detailError.asSharedFlow()

    // -------------------------------------------------------------------------
    // Download state tracking
    // -------------------------------------------------------------------------

    private val _downloadState = MutableStateFlow(DownloadInfo.STATE_INVALID)

    /** Current download state for the displayed gallery. */
    val downloadState: StateFlow<Int> = _downloadState.asStateFlow()

    /**
     * Initialize download state for the given arcid.
     */
    fun initDownloadState(arcid: String?) {
        _downloadState.value = if (!arcid.isNullOrEmpty()) {
            downloadManager.getDownloadState(arcid)
        } else {
            DownloadInfo.STATE_INVALID
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
     * Records [info] in the history table. Fire-and-forget; runs on [Dispatchers.IO].
     */
    fun recordHistory(info: GalleryInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            ServiceRegistry.dataModule.historyRepository.putHistoryInfo(info)
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
     * favorite status. On success, updates [_galleryDetail]. On failure,
     * emits to [_detailError].
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
        val serverUrl = LRRAuthManager.getServerUrl()
        if (arcid.isNullOrEmpty() || serverUrl.isNullOrEmpty()) {
            return false
        }

        val client = ServiceRegistry.networkModule.okHttpClient

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val archive = runSuspend {
                    LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcid)
                }
                val gd = archive.toGalleryDetail()
                val ad = archive.toArchiveDetail()

                // Query LANraragi categories to determine favorite status
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
                    if (matchedNames.isNotEmpty()) {
                        gd.isFavorited = true
                        if (matchedNames.size == 1) {
                            gd.favoriteName = matchedNames[0]
                        } else {
                            gd.favoriteName = matchedNames[0] +
                                categoryInfoSuffix +
                                matchedNames.size +
                                categoryCountSuffix
                        }
                    }
                } catch (catEx: Exception) {
                    android.util.Log.w(
                        TAG,
                        "Failed to query categories for favorite status",
                        catEx
                    )
                    // Non-fatal: favorite status just won't show
                }

                // Cache the detail
                ServiceRegistry.dataModule.archiveDetailCache.put(arcid, ad)

                _galleryDetail.value = gd
                _archiveDetail.value = ad

                // Preload reading pages in background
                triggerReadingPreload(arcid, archive.progress)
            } catch (e: Exception) {
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
            return true
        }
        return true
    }

}
