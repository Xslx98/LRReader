package com.hippo.ehviewer.ui.scene.download

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.callBack.DownloadSearchCallback
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.util.FileUtils
import kotlinx.coroutines.Dispatchers
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.download.DownloadInfoListener
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.sync.DownloadListInfosExecutor
import com.hippo.lib.yorozuya.collect.LongList
import com.hippo.unifile.UniFile
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sealed interface representing all download-related UI events forwarded from
 * [DownloadInfoListener] callbacks. [DownloadsScene] observes
 * [DownloadsViewModel.downloadEvent] and dispatches via `when`.
 */
sealed interface DownloadUiEvent {
    data class ItemAdded(val info: DownloadInfo, val list: List<DownloadInfo>, val position: Int) : DownloadUiEvent
    data class ItemRemoved(val info: DownloadInfo, val list: List<DownloadInfo>, val position: Int) : DownloadUiEvent
    data class ItemUpdated(val info: DownloadInfo, val list: List<DownloadInfo>, val waitList: List<DownloadInfo>) : DownloadUiEvent
    data class DiffUpdate(val tag: String) : DownloadUiEvent
    data class Replaced(val newInfo: DownloadInfo, val oldInfo: DownloadInfo) : DownloadUiEvent
    data class LabelRenamed(val from: String, val to: String) : DownloadUiEvent
    data object LabelDeleted : DownloadUiEvent
    data object LabelsChanged : DownloadUiEvent
    data object Reloaded : DownloadUiEvent
}

/**
 * ViewModel for [DownloadsScene]. Manages download list state, label selection,
 * pagination, search/filter state, and spider info cache.
 *
 * The Scene observes [StateFlow] properties and updates the UI accordingly.
 * View references, adapters, dialog display, and navigation remain in the Scene.
 */
class DownloadsViewModel : ViewModel(), DownloadInfoListener {

    /**
     * The app's [DownloadManager] singleton. Exposed so [DownloadsScene]
     * does not need to reference [ServiceRegistry] directly.
     */
    val downloadManager: DownloadManager = ServiceRegistry.dataModule.downloadManager

    /**
     * Room flow of the persisted download list. Emits whenever the underlying
     * table structure changes (add / remove / state column). Progress updates
     * (speed, downloaded, total) are `@Ignore` fields and continue to be
     * delivered via the [DownloadInfoListener] callback mechanism, forwarded
     * to the Scene as SharedFlow events below.
     */
    val downloadsFlow: Flow<List<DownloadInfo>> = EhDB.observeDownloads()

    // -------------------------------------------------------------------------
    // DownloadInfoListener → sealed DownloadUiEvent forwarding
    // -------------------------------------------------------------------------

    private val _downloadEvent = MutableSharedFlow<DownloadUiEvent>(extraBufferCapacity = 16)

    /** Single event stream for all [DownloadInfoListener] callbacks. */
    val downloadEvent: SharedFlow<DownloadUiEvent> = _downloadEvent.asSharedFlow()

    // -------------------------------------------------------------------------
    // Lifecycle: register/unregister listener
    // -------------------------------------------------------------------------

    init {
        downloadManager.addDownloadInfoListener(this)
    }

    override fun onCleared() {
        super.onCleared()
        downloadManager.removeDownloadInfoListener(this)
    }

    // -------------------------------------------------------------------------
    // Label state
    // -------------------------------------------------------------------------

    private val _currentLabel = MutableStateFlow<String?>(DownloadSettings.getRecentDownloadLabel())

    /** The currently selected download label. null = default (all). */
    val currentLabel: StateFlow<String?> = _currentLabel.asStateFlow()

    /** All available download labels from the DownloadManager. */
    val labelList: List<DownloadLabel>
        get() = downloadManager.labelList

    // -------------------------------------------------------------------------
    // Download list state
    // -------------------------------------------------------------------------

    private val _downloadList = MutableStateFlow<List<DownloadInfo>>(emptyList())

    /** The current (possibly filtered/sorted) list of downloads for the active label. */
    val downloadList: StateFlow<List<DownloadInfo>> = _downloadList.asStateFlow()

    private val _backList = MutableStateFlow<List<DownloadInfo>>(emptyList())

    /** The unfiltered list for the current label. Used as source for filter/sort/search. */
    val backList: StateFlow<List<DownloadInfo>> = _backList.asStateFlow()

    // -------------------------------------------------------------------------
    // Search state
    // -------------------------------------------------------------------------

    private val _searching = MutableStateFlow(false)

    /** Whether a search/filter operation is currently active. */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchKey = MutableStateFlow<String?>(null)

    /** The current search query text. */
    val searchKey: StateFlow<String?> = _searchKey.asStateFlow()

    // -------------------------------------------------------------------------
    // Pagination state
    // -------------------------------------------------------------------------

    private val _indexPage = MutableStateFlow(1)

    /** Current page index (1-based). */
    val indexPage: StateFlow<Int> = _indexPage.asStateFlow()

    private val _pageSize = MutableStateFlow(1)

    /** Number of items per page. */
    val pageSize: StateFlow<Int> = _pageSize.asStateFlow()

    /** Whether pagination is enabled (from settings). */
    val canPagination: Boolean = DownloadSettings.getDownloadPagination()

    /** Threshold above which pagination activates. */
    val paginationSize: Int = PAGINATION_SIZE

    /** Available per-page count choices. */
    val perPageCountChoices: IntArray = PER_PAGE_COUNT_CHOICES

    // -------------------------------------------------------------------------
    // Spider info cache
    // -------------------------------------------------------------------------

    private val _spiderInfoMap = MutableStateFlow<MutableMap<Long, SpiderInfo>>(HashMap())

    /** Cached spider info for reading progress display. */
    val spiderInfoMap: StateFlow<Map<Long, SpiderInfo>> = _spiderInfoMap.asStateFlow()

    // -------------------------------------------------------------------------
    // Label switching
    // -------------------------------------------------------------------------

    /**
     * Sets the current label without refreshing the list.
     * Call [updateForLabel] afterwards to load the list for this label.
     */
    fun selectLabel(label: String?) {
        _currentLabel.value = label
    }

    /**
     * Refreshes the download list for the current label from [DownloadManager].
     * Also resets the back-list (unfiltered source) and persists the label choice.
     */
    fun updateForLabel() {
        val label = _currentLabel.value

        val list: List<DownloadInfo> = if (label == null) {
            downloadManager.defaultDownloadInfoList
        } else {
            val labelList = downloadManager.getLabelDownloadInfoList(label)
            if (labelList == null) {
                // Label no longer exists, fall back to default
                _currentLabel.value = null
                downloadManager.defaultDownloadInfoList
            } else {
                labelList
            }
        }

        _downloadList.value = list
        _backList.value = list

        DownloadSettings.putRecentDownloadLabel(_currentLabel.value)
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    /**
     * Sets the search key and marks search as active.
     */
    fun setSearchKey(key: String?) {
        _searchKey.value = key
    }

    fun setSearching(isSearching: Boolean) {
        _searching.value = isSearching
    }

    // -------------------------------------------------------------------------
    // List mutation (for filter/sort/search results)
    // -------------------------------------------------------------------------

    /**
     * Replaces the current download list.
     */
    fun setDownloadList(list: List<DownloadInfo>) {
        _downloadList.value = list
    }

    // -------------------------------------------------------------------------
    // Category filter state
    // -------------------------------------------------------------------------

    private val _selectedCategory = MutableStateFlow(EhUtils.ALL_CATEGORY)

    /** The currently selected category filter. */
    val selectedCategory: StateFlow<Int> = _selectedCategory.asStateFlow()

    fun setSelectedCategory(category: Int) {
        _selectedCategory.value = category
    }

    // -------------------------------------------------------------------------
    // Filter loading state
    // -------------------------------------------------------------------------

    private val _filterLoading = MutableStateFlow(false)

    /** Whether a filter/sort/search operation is in progress. */
    val filterLoading: StateFlow<Boolean> = _filterLoading.asStateFlow()

    // -------------------------------------------------------------------------
    // One-shot events for list changed
    // -------------------------------------------------------------------------

    private val _listChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted after a category filter produces new results; Scene should refresh UI. */
    val listChanged: SharedFlow<Unit> = _listChanged.asSharedFlow()

    private val _filterSearchDone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted when a filter/sort or search operation completes; Scene should update. */
    val filterSearchDone: SharedFlow<Unit> = _filterSearchDone.asSharedFlow()

    // -------------------------------------------------------------------------
    // Filter / sort / search operations (moved from DownloadFilterHelper)
    // -------------------------------------------------------------------------

    /** Internal callback that routes results back to ViewModel state flows. */
    private val filterCallback = object : DownloadSearchCallback {
        override fun onDownloadSearchSuccess(list: MutableList<DownloadInfo>?) {
            list ?: return
            _downloadList.value = list
            _filterLoading.value = false
            _searching.value = false
            _filterSearchDone.tryEmit(Unit)
        }

        override fun onDownloadListHandleSuccess(list: MutableList<DownloadInfo>?) {
            list ?: return
            _downloadList.value = list
            _filterLoading.value = false
            _filterSearchDone.tryEmit(Unit)
        }

        override fun onDownloadSearchFailed(list: MutableList<DownloadInfo>?) {
            list ?: return
            _downloadList.value = list
            _filterLoading.value = false
            _searching.value = false
            _filterSearchDone.tryEmit(Unit)
        }
    }

    /**
     * Execute a filter-and-sort operation for the given menu item [id].
     */
    fun gotoFilterAndSort(id: Int) {
        _filterLoading.value = true
        val executor = DownloadListInfosExecutor(_backList.value, downloadManager)
        executor.setDownloadSearchingListener(filterCallback)
        executor.executeFilterAndSort(id)
    }

    /**
     * Execute a search on the current download list.
     */
    fun startSearching(searchKey: String) {
        _filterLoading.value = true
        val executor = DownloadListInfosExecutor(_downloadList.value, searchKey)
        executor.setDownloadSearchingListener(filterCallback)
        executor.executeSearching()
    }

    /**
     * Apply category filter to the back-list and update the download list.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun filterByCategory() {
        val backList = _backList.value
        val category = _selectedCategory.value
        if (category == EhUtils.ALL_CATEGORY) {
            _downloadList.value = ArrayList(backList)
        } else {
            val filtered = ArrayList<DownloadInfo>()
            for (info in backList) {
                if (info.category == category) {
                    filtered.add(info)
                }
            }
            _downloadList.value = filtered
        }
        _listChanged.tryEmit(Unit)
    }

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    fun setIndexPage(page: Int) {
        _indexPage.value = page
    }

    fun setPageSize(size: Int) {
        _pageSize.value = size
    }

    /**
     * Calculates the position within the full list for a given in-page position.
     */
    fun positionInList(position: Int): Int {
        val list = _downloadList.value
        if (list.size > PAGINATION_SIZE && canPagination) {
            return position + _pageSize.value * (_indexPage.value - 1)
        }
        return position
    }

    /**
     * Calculates the in-page index for a given position in the full list.
     */
    fun listIndexInPage(position: Int): Int {
        val list = _downloadList.value
        if (list.size > PAGINATION_SIZE && canPagination) {
            return position % _pageSize.value
        }
        return position
    }

    // -------------------------------------------------------------------------
    // Spider info
    // -------------------------------------------------------------------------

    fun removeSpiderInfo(gid: Long) {
        _spiderInfoMap.value.remove(gid)
    }

    fun putSpiderInfo(gid: Long, info: SpiderInfo) {
        _spiderInfoMap.value[gid] = info
    }

    fun putAllSpiderInfo(map: Map<Long, SpiderInfo>) {
        _spiderInfoMap.value.putAll(map)
    }

    // -------------------------------------------------------------------------
    // Label rename / change
    // -------------------------------------------------------------------------

    /**
     * Handles a label rename event. If the current label matches [from],
     * updates the selection to [to]. Call [updateForLabel] afterwards.
     */
    fun handleLabelRenamed(from: String, to: String) {
        if (_currentLabel.value == from) {
            _currentLabel.value = to
        }
    }

    /**
     * Resets to the default label. Call [updateForLabel] afterwards.
     */
    fun resetToDefaultLabel() {
        _currentLabel.value = null
    }

    // -------------------------------------------------------------------------
    // Download operations (moved from DownloadLabelHelper)
    // -------------------------------------------------------------------------

    /**
     * Deletes a single download. If [deleteFiles] is true, also removes the
     * download directory and dirname DB record.
     */
    fun deleteSingleDownload(galleryInfo: GalleryInfo, deleteFiles: Boolean) {
        downloadManager.deleteDownload(galleryInfo.gid)
        DownloadSettings.putRemoveImageFiles(deleteFiles)
        if (deleteFiles) {
            val gid = galleryInfo.gid
            ServiceRegistry.coroutineModule.ioScope.launch {
                EhDB.removeDownloadDirnameAsync(gid)
                val file = SpiderDen.getGalleryDownloadDir(galleryInfo)
                file?.delete()
            }
        }
    }

    /**
     * Deletes a range of downloads. If [deleteFiles] is true, also removes
     * the download directories and dirname DB records.
     */
    fun deleteRangeDownloads(
        downloadInfoList: List<DownloadInfo>,
        gidList: LongList,
        deleteFiles: Boolean
    ) {
        downloadManager.deleteRangeDownload(gidList)
        DownloadSettings.putRemoveImageFiles(deleteFiles)
        if (deleteFiles) {
            // Snapshot the list to avoid concurrent modification
            val infos = ArrayList(downloadInfoList)
            ServiceRegistry.coroutineModule.ioScope.launch {
                for (info in infos) {
                    EhDB.removeDownloadDirnameAsync(info.gid)
                    val file = SpiderDen.getGalleryDownloadDir(info)
                    file?.delete()
                }
            }
        }
    }

    /**
     * Moves downloads to a new label.
     */
    fun moveDownloads(downloadInfoList: List<DownloadInfo>, label: String?) {
        downloadManager.changeLabel(downloadInfoList, label)
    }

    /**
     * Stops a range of downloads by gid list.
     */
    fun stopRangeDownloads(gidList: LongList) {
        downloadManager.stopRangeDownload(gidList)
    }

    // -------------------------------------------------------------------------
    // Archive import (moved from DownloadImportHelper)
    // -------------------------------------------------------------------------

    /** One-shot event: emitted with a string resource ID for toast display. */
    private val _importToast = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val importToast: SharedFlow<Int> = _importToast.asSharedFlow()

    /** One-shot event: emitted when an import completes successfully. */
    private val _importSuccess = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val importSuccess: SharedFlow<Unit> = _importSuccess.asSharedFlow()

    /**
     * Processes a selected archive URI: validates, creates DownloadInfo,
     * and adds to download manager. Runs on [Dispatchers.IO].
     */
    fun processArchiveImport(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Verify URI accessibility
                try {
                    context.contentResolver.openInputStream(uri)?.use { }
                        ?: run {
                            _importToast.tryEmit(com.hippo.ehviewer.R.string.import_archive_failed)
                            return@launch
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot access file even with persistent permission", e)
                    _importToast.tryEmit(com.hippo.ehviewer.R.string.import_archive_failed)
                    return@launch
                }

                // Get file name
                val fileName: String = FileUtils.getFileName(context, uri)
                    ?: "imported_archive_${System.currentTimeMillis()}"

                // Validate file format
                if (!isValidArchiveFormat(fileName)) {
                    _importToast.tryEmit(com.hippo.ehviewer.R.string.import_archive_invalid_format)
                    return@launch
                }

                // Create DownloadInfo for the archive
                val downloadInfo = createArchiveDownloadInfo(uri, fileName)
                if (downloadInfo == null) {
                    _importToast.tryEmit(com.hippo.ehviewer.R.string.import_archive_failed)
                    return@launch
                }

                // Check if already imported
                if (downloadManager.containDownloadInfo(downloadInfo.gid)) {
                    _importToast.tryEmit(com.hippo.ehviewer.R.string.import_archive_already_imported)
                    return@launch
                }

                // Add to download manager
                val downloadList = ArrayList<DownloadInfo>()
                downloadList.add(downloadInfo)
                downloadManager.addDownload(downloadList)
                _importToast.tryEmit(com.hippo.ehviewer.R.string.import_archive_success)
                _importSuccess.tryEmit(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process archive file", e)
                _importToast.tryEmit(com.hippo.ehviewer.R.string.import_archive_failed)
            }
        }
    }

    private fun isValidArchiveFormat(fileName: String?): Boolean {
        if (fileName == null) return false
        val lowerName = fileName.lowercase()
        return lowerName.endsWith(".zip") || lowerName.endsWith(".rar") ||
            lowerName.endsWith(".cbz") || lowerName.endsWith(".cbr")
    }

    private fun createArchiveDownloadInfo(uri: Uri, fileName: String): DownloadInfo? {
        return try {
            DownloadInfo().apply {
                gid = System.currentTimeMillis()
                token = ""
                title = fileName.replace("\\.[^.]*$".toRegex(), "")
                titleJpn = null
                thumb = null
                category = EhUtils.UNKNOWN
                posted = null
                uploader = "Local Archive"
                rating = -1.0f
                state = DownloadInfo.STATE_FINISH
                legacy = 0
                time = System.currentTimeMillis()
                label = null
                total = 0
                finished = 0
                archiveUri = uri.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create DownloadInfo", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // DownloadInfoListener implementation → sealed event emission
    // -------------------------------------------------------------------------

    override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
        _downloadEvent.tryEmit(DownloadUiEvent.ItemAdded(info, list, position))
    }

    override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
        _downloadEvent.tryEmit(DownloadUiEvent.Replaced(newInfo, oldInfo))
    }

    override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>, mWaitList: List<DownloadInfo>) {
        _downloadEvent.tryEmit(DownloadUiEvent.ItemUpdated(info, list, mWaitList))
    }

    override fun onUpdateAll() {
        _downloadEvent.tryEmit(DownloadUiEvent.DiffUpdate("updateAll"))
    }

    override fun onReload() {
        _downloadEvent.tryEmit(DownloadUiEvent.Reloaded)
    }

    override fun onChange() {
        _downloadEvent.tryEmit(DownloadUiEvent.LabelDeleted)
    }

    override fun onRenameLabel(from: String, to: String) {
        _downloadEvent.tryEmit(DownloadUiEvent.LabelRenamed(from, to))
    }

    override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
        _downloadEvent.tryEmit(DownloadUiEvent.ItemRemoved(info, list, position))
    }

    override fun onUpdateLabels() {
        _downloadEvent.tryEmit(DownloadUiEvent.LabelsChanged)
    }

    companion object {
        private const val TAG = "DownloadsViewModel"
        /** Threshold above which pagination activates. */
        private const val PAGINATION_SIZE = 500

        /** Available per-page count choices for the pagination indicator. */
        private val PER_PAGE_COUNT_CHOICES = intArrayOf(50, 100, 200, 300, 500)

        private fun deleteFileAsync(vararg files: UniFile?) {
            ServiceRegistry.coroutineModule.ioScope.launch {
                for (file in files) {
                    file?.delete()
                }
            }
        }
    }
}
