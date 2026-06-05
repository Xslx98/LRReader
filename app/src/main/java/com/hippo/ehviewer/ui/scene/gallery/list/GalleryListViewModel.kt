package com.hippo.ehviewer.ui.scene.gallery.list

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRArchivePagingSource
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.client.api.LRRClientProvider
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadInfoListener
import com.hippo.ehviewer.download.DownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

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
    val galleryFlow: Flow<PagingData<Archive>> = searchParams.flatMapLatest { params ->
        Pager<Int, Archive>(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                // Keep initialLoadSize == pageSize. The PagingSource computes its
                // request offset as `page * params.loadSize`, so a refresh that
                // used the default initialLoadSize (3 * pageSize) while appends
                // use pageSize would mis-align the offset and re-request rows the
                // refresh already returned — duplicate archives in the list.
                initialLoadSize = PAGE_SIZE,
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
        /** A single download was updated; refresh the item with this [arcid]. */
        data class ItemUpdated(val arcid: String) : DownloadEvent

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
                _downloadEvent.tryEmit(DownloadEvent.ItemUpdated(info.arcid))
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
    // Archive upload (pre-upload dedup + determinate progress)
    // -------------------------------------------------------------------------

    /**
     * UI state for the archive upload flow. The Scene renders a determinate
     * progress dialog from [Preparing]/[Uploading] and a one-shot tip from the
     * terminal states ([DuplicateSkipped]/[Success]/[Failed]), then calls
     * [resetUploadState] to return to [Idle].
     */
    sealed interface UploadUiState {
        data object Idle : UploadUiState

        /** Reading the leading bytes and probing the server for a duplicate. */
        data object Preparing : UploadUiState

        /** The archive already exists on the server; the upload was skipped. */
        data class DuplicateSkipped(val arcid: String) : UploadUiState

        /** Transferring bytes; [percent] is 0..100. */
        data class Uploading(val percent: Int) : UploadUiState

        data class Success(val arcid: String) : UploadUiState

        /** [error] is mapped to a localized message by the Scene (it has a Context). */
        data class Failed(val error: Exception) : UploadUiState
    }

    /**
     * Everything the upload needs from the picked content [android.net.Uri],
     * resolved on the Android side so this ViewModel stays Context-free.
     * [openStream] must open a *fresh* stream each call (it is invoked twice:
     * once to fingerprint the leading bytes, once to copy the full file).
     */
    class UploadRequest(
        val displayName: String,
        val cacheDir: File,
        val openStream: () -> InputStream?
    )

    private val _uploadState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)

    /** Observe to drive the upload progress dialog and result tips. */
    val uploadState: StateFlow<UploadUiState> = _uploadState.asStateFlow()

    private var uploadJob: Job? = null

    /**
     * Upload an archive with a cheap pre-upload duplicate check. The archive id
     * (SHA-1 of the first 512 KB) is computed locally and probed against the
     * server; an existing archive short-circuits to [UploadUiState.DuplicateSkipped]
     * without transferring — or even fully copying — the file. Otherwise the file
     * is uploaded with byte-level [UploadUiState.Uploading] progress.
     */
    fun uploadArchive(req: UploadRequest) {
        if (uploadJob?.isActive == true) return // ignore re-entrant taps while busy
        uploadJob = viewModelScope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                _uploadState.value = UploadUiState.Preparing

                val arcid = (req.openStream() ?: throw IOException("Cannot open file"))
                    .use { LRRArchiveApi.computeArchiveId(it) }

                // Best-effort: an inconclusive probe (network/proxy error) is
                // treated as "not a confirmed duplicate" so the upload still runs
                // and the server's own check remains the backstop.
                val isDuplicate = runCatching { LRRArchiveApi.archiveExists(arcid) }
                    .getOrDefault(false)
                if (isDuplicate) {
                    _uploadState.value = UploadUiState.DuplicateSkipped(arcid)
                    return@launch
                }

                val target = File(req.cacheDir, req.displayName)
                tempFile = target
                req.openStream().use { input ->
                    if (input == null) throw IOException("Cannot open file")
                    FileOutputStream(target).use { fos -> input.copyTo(fos) }
                }

                _uploadState.value = UploadUiState.Uploading(0)
                val checksum = LRRArchiveApi.computeFileChecksum(target)
                var lastPercent = -1
                val arcidUploaded = LRRArchiveApi.uploadArchive(
                    target,
                    fileChecksum = checksum,
                    progressListener = { written, total ->
                        if (total > 0) {
                            val pct = (written * PERCENT_MAX / total).toInt().coerceIn(0, PERCENT_MAX)
                            if (pct != lastPercent) {
                                lastPercent = pct
                                _uploadState.value = UploadUiState.Uploading(pct)
                            }
                        }
                    }
                )
                _uploadState.value = UploadUiState.Success(arcidUploaded.ifEmpty { arcid })
            } catch (e: CancellationException) {
                _uploadState.value = UploadUiState.Idle
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                _uploadState.value = UploadUiState.Failed(e)
            } finally {
                tempFile?.takeIf { it.exists() }?.delete()
            }
        }
    }

    /** Cancel an in-flight upload (e.g. the user dismissed the progress dialog). */
    fun cancelUpload() {
        uploadJob?.cancel()
    }

    /** Return to [UploadUiState.Idle] after the Scene has handled a terminal state. */
    fun resetUploadState() {
        _uploadState.value = UploadUiState.Idle
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
        private const val TAG = "GalleryListViewModel"

        /** Page size for gallery list. Kept smaller than LANraragi default (100) to reduce memory. */
        const val PAGE_SIZE = 50

        /** Number of items before the end to start loading the next page. */
        const val PREFETCH_DISTANCE = 10

        /** Upload progress is reported on a 0..100 scale. */
        private const val PERCENT_MAX = 100
    }
}
