package com.hippo.ehviewer.ui.scene.download

import androidx.lifecycle.ViewModel
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for [DownloadsScene]. Manages download list state, label selection,
 * pagination, search/filter state, and spider info cache.
 *
 * The Scene observes [StateFlow] properties and updates the UI accordingly.
 * View references, adapters, dialog display, and navigation remain in the Scene.
 */
class DownloadsViewModel : ViewModel() {

    private val downloadManager: DownloadManager = ServiceRegistry.dataModule.downloadManager

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

    private val _downloadList = MutableStateFlow<MutableList<DownloadInfo>>(mutableListOf())

    /** The current (possibly filtered/sorted) list of downloads for the active label. */
    val downloadList: StateFlow<MutableList<DownloadInfo>> = _downloadList.asStateFlow()

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
    @Suppress("UNCHECKED_CAST")
    fun updateForLabel() {
        val label = _currentLabel.value

        val list: MutableList<DownloadInfo> = if (label == null) {
            downloadManager.defaultDownloadInfoList as MutableList<DownloadInfo>
        } else {
            val labelList = downloadManager.getLabelDownloadInfoList(label) as? MutableList<DownloadInfo>
            if (labelList == null) {
                // Label no longer exists, fall back to default
                _currentLabel.value = null
                downloadManager.defaultDownloadInfoList as MutableList<DownloadInfo>
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
     * Replaces the current download list. Used by [DownloadFilterHelper] after
     * a filter/sort/search operation completes.
     */
    fun setDownloadList(list: MutableList<DownloadInfo>) {
        _downloadList.value = list
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
    fun onLabelRenamed(from: String, to: String) {
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

    companion object {
        /** Threshold above which pagination activates. */
        private const val PAGINATION_SIZE = 500

        /** Available per-page count choices for the pagination indicator. */
        private val PER_PAGE_COUNT_CHOICES = intArrayOf(50, 100, 200, 300, 500)
    }
}
