package com.hippo.ehviewer.ui.scene.gallery.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfoUi
import com.lanraragi.reader.client.api.LRRArchivePagingSource
import com.lanraragi.reader.client.api.LRRClientProvider
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadInfoListener
import com.hippo.ehviewer.download.DownloadManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * ViewModel for the gallery list screen. Exposes a [Flow] of [PagingData]
 * that automatically invalidates when search parameters change.
 *
 * Also manages [DownloadInfoListener] and [FavouriteStatusRouter.Listener]
 * registrations so the Scene does not need to hold those listeners directly.
 * The Scene observes [downloadEvent] and [favouriteStatusChanged] to update
 * specific adapter items.
 */
class GalleryListViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // Service accessors (read-through to ServiceRegistry so the Scene does not
    // need to import ServiceRegistry directly)
    // -------------------------------------------------------------------------

    /** The app's [DownloadManager] singleton. */
    val downloadManager: DownloadManager
        get() = ServiceRegistry.dataModule.downloadManager

    /** The global favourite status router. */
    val favouriteStatusRouter: FavouriteStatusRouter
        get() = ServiceRegistry.dataModule.favouriteStatusRouter

    /**
     * Encapsulates all search parameters needed to create a [LRRArchivePagingSource].
     */
    data class SearchParams(
        val filter: String? = null,
        val category: String? = null,
        val sortby: String? = "date_added",
        val order: String? = "desc",
        val newonly: Boolean = false,
        val untaggedonly: Boolean = false
    )

    private val searchParams = MutableStateFlow(SearchParams())

    @OptIn(ExperimentalCoroutinesApi::class)
    val galleryFlow: Flow<PagingData<GalleryInfoUi>> = searchParams.flatMapLatest { params ->
        Pager<Int, GalleryInfoUi>(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                prefetchDistance = PREFETCH_DISTANCE
            )
        ) {
            LRRArchivePagingSource(
                client = LRRClientProvider.getClient(),
                baseUrl = LRRClientProvider.getBaseUrl(),
                filter = params.filter,
                category = params.category,
                sortby = params.sortby,
                order = params.order,
                newonly = params.newonly,
                untaggedonly = params.untaggedonly
            )
        }.flow
    }.cachedIn(viewModelScope)

    /**
     * Trigger a new search. The existing PagingData is automatically invalidated
     * and a fresh load begins from page 0.
     */
    fun search(params: SearchParams) {
        searchParams.value = params
    }

    // -------------------------------------------------------------------------
    // Download state observation
    // -------------------------------------------------------------------------

    /**
     * Sealed interface for download events relevant to the gallery list.
     * The Scene observes these to refresh specific adapter items.
     */
    sealed interface DownloadEvent {
        /** A single download was updated; refresh the item with this [gid]. */
        data class ItemUpdated(val gid: Long) : DownloadEvent

        /** Broad change (add/remove/reload/change); refresh all visible items. */
        data object BulkChanged : DownloadEvent
    }

    private val _downloadEvent = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)

    /** Emitted when download state changes. Observe to update adapter items. */
    val downloadEvent: SharedFlow<DownloadEvent> = _downloadEvent.asSharedFlow()

    private var downloadInfoListener: DownloadInfoListener? = null
    private var observingDownloads = false

    /**
     * Register a [DownloadInfoListener] on the [DownloadManager]. The listener
     * translates callbacks into [DownloadEvent] emissions. Safe to call multiple
     * times; only the first call takes effect.
     */
    fun startObservingDownloads() {
        if (observingDownloads) return
        observingDownloads = true

        val listener = object : DownloadInfoListener {
            override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                _downloadEvent.tryEmit(DownloadEvent.BulkChanged)
            }

            override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
                // No-op: replace does not affect gallery list display
            }

            override fun onUpdate(
                info: DownloadInfo,
                list: List<DownloadInfo>,
                mWaitList: List<DownloadInfo>
            ) {
                _downloadEvent.tryEmit(DownloadEvent.ItemUpdated(info.gid))
            }

            override fun onUpdateAll() {
                // No-op: bulk state change without visual impact on gallery list
            }

            override fun onReload() {
                _downloadEvent.tryEmit(DownloadEvent.BulkChanged)
            }

            override fun onChange() {
                _downloadEvent.tryEmit(DownloadEvent.BulkChanged)
            }

            override fun onRenameLabel(from: String, to: String) {
                // No-op: label changes don't affect gallery list display
            }

            override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                _downloadEvent.tryEmit(DownloadEvent.BulkChanged)
            }

            override fun onUpdateLabels() {
                // No-op: label changes don't affect gallery list display
            }
        }
        downloadInfoListener = listener
        downloadManager.addDownloadInfoListener(listener)
    }

    // -------------------------------------------------------------------------
    // Favourite status observation
    // -------------------------------------------------------------------------

    private val _favouriteStatusChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Emitted when any gallery's favourite status changes. Refresh all visible items. */
    val favouriteStatusChanged: SharedFlow<Unit> = _favouriteStatusChanged.asSharedFlow()

    private var favouriteStatusRouterListener: FavouriteStatusRouter.Listener? = null
    private var observingFavourites = false

    /**
     * Register a [FavouriteStatusRouter.Listener]. Safe to call multiple times;
     * only the first call takes effect.
     */
    fun startObservingFavourites() {
        if (observingFavourites) return
        observingFavourites = true

        val listener = FavouriteStatusRouter.Listener { _, _ ->
            _favouriteStatusChanged.tryEmit(Unit)
        }
        favouriteStatusRouterListener = listener
        favouriteStatusRouter.addListener(listener)
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCleared() {
        downloadInfoListener?.let { downloadManager.removeDownloadInfoListener(it) }
        favouriteStatusRouterListener?.let { favouriteStatusRouter.removeListener(it) }
        super.onCleared()
    }

    companion object {
        /** Page size for gallery list. Kept smaller than LANraragi default (100) to reduce memory. */
        const val PAGE_SIZE = 50

        /** Number of items before the end to start loading the next page. */
        const val PREFETCH_DISTANCE = 10
    }
}
