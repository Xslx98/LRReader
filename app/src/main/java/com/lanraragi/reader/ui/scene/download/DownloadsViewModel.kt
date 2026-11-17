package com.lanraragi.reader.ui.scene.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.settings.DownloadSettings
import com.lanraragi.reader.download.search.DownloadSearchCallback
import com.lanraragi.reader.dao.DownloadInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import com.lanraragi.reader.dao.DownloadLabel
import com.lanraragi.reader.download.DownloadInfoListener
import com.lanraragi.reader.download.DownloadManager
import com.lanraragi.reader.download.ProgressSnapshot
import com.lanraragi.reader.download.TankDownloadGrouping
import com.lanraragi.reader.download.TankProgressAggregate
import com.lanraragi.reader.download.search.DownloadListInfosExecutor
import com.lanraragi.reader.download.search.DownloadSearchMatcher
import com.lanraragi.reader.storage.StorageFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import com.lanraragi.reader.download.DownloadDirCache
import com.lanraragi.reader.ui.list.SelectionModel
import com.lanraragi.reader.R

/**
 * Sealed interface representing all download-related UI events forwarded from
 * [DownloadInfoListener] callbacks. [DownloadsScene] observes
 * [DownloadsViewModel.downloadEvent] and dispatches via `when`.
 */
sealed interface DownloadUiEvent {
    data class Replaced(val newInfo: DownloadInfo, val oldInfo: DownloadInfo) : DownloadUiEvent
    data class LabelRenamed(val from: String, val to: String) : DownloadUiEvent
    data object LabelDeleted : DownloadUiEvent
    data object LabelsChanged : DownloadUiEvent
}

/**
 * ViewModel for [DownloadsScene]. Manages download list state, label selection,
 * pagination, search/filter state, and spider info cache.
 *
 * The Scene observes [StateFlow] properties and updates the UI accordingly.
 * View references, adapters, dialog display, and navigation remain in the Scene.
 *
 * @param computeDispatcher runs the O(N) grouping and label filter of every
 *   downloads-list emission off the main thread (audit C17).
 */
class DownloadsViewModel @JvmOverloads constructor(
    // First, so the ViewModel factory finds the (SavedStateHandle) constructor.
    saved: SavedStateHandle = SavedStateHandle(),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel(), DownloadInfoListener {

    /** The list's multi-select, by arcid (tank cards by TANK_ id); saved with the screen. */
    val selection = SelectionModel(saved)

    /**
     * The app's [DownloadManager] singleton. Exposed so [DownloadsScene]
     * does not need to reference [ServiceRegistry] directly.
     */
    val downloadManager: DownloadManager = ServiceRegistry.dataModule.downloadManager

    /**
     * Room flow of the persisted download list. Emits on every structural
     * change (add / remove / label / state / title / time). Transient progress
     * (speed / downloaded / total / remaining) is not carried here — it lives
     * on [progressMap] below. Split architecture per ADR-001 Option D.
     */
    val downloadsFlow: Flow<List<DownloadInfo>> =
        ServiceRegistry.dataModule.downloadDbRepository.observeDownloads()

    /**
     * Live per-archive progress map, pass-through from
     * [DownloadManager.progressTracker]. The [DownloadsScene] subscribes to
     * this separately and dispatches `notifyItemChanged(pos, PAYLOAD_PROGRESS)`
     * so only the progress views repaint on a tick — the list itself does
     * not need to re-emit for a progress update.
     *
     * This split is the root cause fix for the progress-freeze bug introduced
     * by the earlier mutating-combine design (W35-3b post-mortem): sharing
     * mutable [DownloadInfo] instances across Flow emissions defeats
     * [MutableStateFlow] equality-based conflation and DiffUtil alike.
     */
    val progressMap: StateFlow<Map<String, ProgressSnapshot>> =
        downloadManager.progressTracker.progressFlow

    // -------------------------------------------------------------------------
    // DownloadInfoListener → sealed DownloadUiEvent forwarding
    // -------------------------------------------------------------------------

    private val _downloadEvent = MutableSharedFlow<DownloadUiEvent>(extraBufferCapacity = 16)

    /** Single event stream for all [DownloadInfoListener] callbacks. */
    val downloadEvent: SharedFlow<DownloadUiEvent> = _downloadEvent.asSharedFlow()

    // -------------------------------------------------------------------------
    // Lifecycle: register/unregister listener
    //
    // [roomFlowJob] is started from [init] after all state fields are set up
    // because [startObservingRoomFlow] reads [_currentLabel] inside its
    // collectLatest lambda. Kept as a property so [updateForLabel] can
    // restart collection when the label changes.
    // -------------------------------------------------------------------------

    private var roomFlowJob: Job? = null

    /**
     * Subscribe to [downloadsFlow], filter by the active label, and publish to
     * [_downloadList] + [_backList]. Emissions are structural only — progress
     * ticks flow via [progressMap]. Restarted when the label changes via
     * [updateForLabel].
     */
    private fun startObservingRoomFlow() {
        roomFlowJob?.cancel()
        roomFlowJob = viewModelScope.launch {
            // Tank grouping (Track 2) runs BEFORE the label filter: tagged
            // member rows fold into one synthetic card per live group
            // (rendered in the default list; members leave every label
            // list while tagged).
            combine(
                downloadsFlow,
                ServiceRegistry.dataModule.downloadDbRepository.observeTankGroups(),
            ) { allDownloads, groups ->
                forgetDirsOfRemoved(allDownloads)
                TankDownloadGrouping.group(allDownloads, groups)
            }
                // Grouping and the label filter are O(N) over every download
                // row and used to run on Main for each emission (audit C17).
                // The rows are fresh Room decodes, not the main-thread-owned
                // DownloadManager objects, so reading them here is safe.
                .flowOn(computeDispatcher)
                .collectLatest { grouped ->
                    _tankMembers.value = grouped.tankMembers
                    val label = _currentLabel.value
                    val filtered = withContext(computeDispatcher) {
                        grouped.display.filter { it.label == label }
                    }
                    // The active search and order apply to every emission, or
                    // the next table change would republish the whole label list.
                    listLoaded = true
                    _backList.value = filtered
                    show(filtered)
                }
        }
    }

    /** Arcids of the last Room emission, see [forgetDirsOfRemoved]. */
    private var knownArcids: Set<String> = emptySet()

    /**
     * A download that left the table (single or bulk delete) drops its
     * cached directory, so a later re-download of the same arcid resolves
     * its new one. Runs in the single list collector, in emission order.
     */
    private fun forgetDirsOfRemoved(allDownloads: List<DownloadInfo>) {
        val arcids = allDownloads.mapTo(HashSet()) { it.arcid }
        for (gone in knownArcids) if (gone !in arcids) downloadDirCache.invalidate(gone)
        knownArcids = arcids
    }

    // -------------------------------------------------------------------------
    // Tank download grouping (Track 2)
    // -------------------------------------------------------------------------

    private val _tankMembers = MutableStateFlow<Map<String, List<DownloadInfo>>>(emptyMap())

    /** Live member rows behind each tank card, keyed by tank id. */
    val tankMembers: StateFlow<Map<String, List<DownloadInfo>>> = _tankMembers.asStateFlow()

    /** Member rows of one tank card (empty when the group vanished). */
    fun tankMembersOf(tankId: String): List<DownloadInfo> =
        _tankMembers.value[tankId].orEmpty()

    /**
     * Aggregate progress snapshot for a tank card over the live progress map
     * (members without a snapshot contribute nothing — the card's bar is
     * honest about what is actually known); null when no member has one.
     */
    fun tankProgressSnapshot(tankId: String): ProgressSnapshot? =
        TankProgressAggregate.of(tankId, tankMembersOf(tankId)) { downloadManager.progressFor(it) }

    /** Tank card id that folds [arcid] on the current display, or null for a standalone row. */
    fun tankCardIdFor(arcid: String): String? {
        for ((tankId, members) in _tankMembers.value) {
            if (members.any { it.arcid == arcid }) return tankId
        }
        return null
    }

    override fun onCleared() {
        super.onCleared()
        roomFlowJob?.cancel()
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

    /**
     * Room has published the list at least once. Until then [downloadList]
     * holds its empty start value, which says nothing about which rows exist
     * (a restored selection must not be pruned against it).
     */
    private var listLoaded = false

    /**
     * Drops the checks of rows that left [list], the list just shown. Not
     * before Room's first emission: a selection restored after process death
     * would be wiped against the empty start value.
     */
    fun retainSelection(list: List<DownloadInfo>) {
        if (listLoaded) selection.retainAll(list.map { it.arcid })
    }

    /** The current (possibly filtered/sorted) list of downloads for the active label. */
    val downloadList: StateFlow<List<DownloadInfo>> = _downloadList.asStateFlow()

    private val _backList = MutableStateFlow<List<DownloadInfo>>(emptyList())

    /** The unfiltered list for the current label. Used as source for filter/sort/search. */
    val backList: StateFlow<List<DownloadInfo>> = _backList.asStateFlow()

    // -------------------------------------------------------------------------
    // Search state (declared before init: the Room collector it starts reads
    // the search key, possibly synchronously)
    // -------------------------------------------------------------------------

    private val _filter = MutableStateFlow(DownloadsFilter())

    /** The list's search and filter pass; see [DownloadsFilter]. */
    val filter: StateFlow<DownloadsFilter> = _filter.asStateFlow()

    init {
        downloadManager.addDownloadInfoListener(this)
        startObservingRoomFlow()
    }

    // -------------------------------------------------------------------------
    // Pagination state
    // -------------------------------------------------------------------------

    private val _indexPage = MutableStateFlow(1)

    /** Current page index (1-based). */
    val indexPage: StateFlow<Int> = _indexPage.asStateFlow()

    private val _pageSize = MutableStateFlow(PER_PAGE_COUNT_CHOICES[0])

    /** Number of items per page. */
    val pageSize: StateFlow<Int> = _pageSize.asStateFlow()

    /** Whether pagination is enabled (from settings). */
    private val canPagination: Boolean = DownloadSettings.getDownloadPagination()

    /** Whether a list of [size] rows is shown page by page: the one home for this rule. */
    fun isPaginated(size: Int): Boolean = canPagination && size >= PAGINATION_SIZE

    /** Available per-page count choices. */
    val perPageCountChoices: IntArray = PER_PAGE_COUNT_CHOICES

    // -------------------------------------------------------------------------
    // Download dir cache (thumbnail binds)
    // -------------------------------------------------------------------------

    /**
     * Per-arcid download-dir resolutions shared by every thumbnail bind.
     * Resolves on the application scope (not viewModelScope) because
     * ThumbDataContainer blocks on the future from cover-loader I/O threads —
     * see [DownloadDirCache]. Invalidated from the [DownloadInfoListener]
     * callbacks below so a deleted-then-re-added archive re-resolves.
     */
    private val downloadDirCache = DownloadDirCache()

    /** The shared (possibly in-flight) download-dir resolution for [info]. */
    fun downloadDirFutureFor(info: DownloadInfo): CompletableFuture<StorageFile?> =
        downloadDirCache.futureFor(info)

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
     * Refreshes the download list for the current label.
     *
     * Restarts the Room Flow collection with the new label filter.
     * The Flow will emit the filtered list asynchronously. Also persists
     * the label choice.
     */
    fun updateForLabel() {
        DownloadSettings.putRecentDownloadLabel(_currentLabel.value)
        // Re-start collection — collectLatest in startObservingRoomFlow()
        // will pick up the new _currentLabel.value on the next emission.
        startObservingRoomFlow()
    }

    // -------------------------------------------------------------------------
    // Filter / sort / search operations (moved from DownloadFilterHelper)
    // -------------------------------------------------------------------------

    /** The latest list shown or pass started; an older pass's result is dropped. */
    private var pass = 0

    /** A filter or sort pass finished (main thread): show its list; the list collector redraws. */
    private fun passCallback(id: Int) = object : DownloadSearchCallback {
        override fun onDownloadSearchSuccess(mList: List<DownloadInfo>) = publishPass(id, mList)
        override fun onDownloadListHandleSuccess(mList: List<DownloadInfo>) = publishPass(id, mList)
        override fun onDownloadSearchFailed(mList: List<DownloadInfo>) = publishPass(id, mList)
    }

    private fun publishPass(id: Int, list: List<DownloadInfo>) {
        if (id != pass) return
        _downloadList.value = list
        _filter.update { it.copy(applying = false) }
    }

    /**
     * Shows [labelList] (the current label's rows) through the search and
     * the order. Without an order it shows at once; with one the list
     * shown stays until the order pass lands (sizes are read on IO).
     */
    private fun show(labelList: List<DownloadInfo>) {
        val searched = applySearch(labelList)
        val order = _filter.value.order
        if (order == null) {
            pass++
            _downloadList.value = searched
            return
        }
        // Filter/sort the searched list, so a filter narrows the search rather than replacing it.
        val executor = DownloadListInfosExecutor(searched, downloadManager)
        executor.setDownloadSearchingListener(passCallback(++pass))
        executor.executeFilterAndSort(order)
    }

    /**
     * Applies the state filter or sort of menu item [id] and keeps it for
     * every later list, until "All" or "Default" clears it.
     */
    fun gotoFilterAndSort(id: Int) {
        val order = id.takeUnless { it == R.id.all || it == R.id.sort_by_default }
        _filter.update { it.copy(order = order, applying = order != null) }
        show(_backList.value)
    }

    private fun applySearch(list: List<DownloadInfo>): List<DownloadInfo> {
        val key = _filter.value.query ?: return list
        return list.filter { DownloadSearchMatcher.matches(it, key) }
    }

    /**
     * Searches the current label's list for [query] (not the previous result,
     * which a new query must be able to widen); a blank query shows the whole
     * label. The search runs here, on the caller's thread; an order pass after
     * it is tagged, so an older pass landing later is dropped.
     */
    fun search(query: String?) {
        _filter.update { it.copy(query = query?.takeIf { q -> q.isNotBlank() }) }
        show(_backList.value)
    }

    /**
     * Drop the search and the order when the Downloads page goes away; the
     * label list shows again. A pass still running is forgotten too, so it
     * cannot keep the label drawer locked.
     */
    fun clearSearch() {
        _filter.value = DownloadsFilter()
        show(_backList.value)
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
        if (isPaginated(_downloadList.value.size)) {
            return position + _pageSize.value * (_indexPage.value - 1)
        }
        return position
    }

    /**
     * Calculates the in-page index for a given position in the full list.
     */
    fun listIndexInPage(position: Int): Int {
        if (isPaginated(_downloadList.value.size)) {
            return position % _pageSize.value
        }
        return position
    }

    /**
     * Index in the full list of the row showing [arcid], or -1. Rows are matched
     * by arcid because the list holds its own Room decodes, not the manager's
     * objects; a tagged tank member shows as its tank's card.
     */
    fun listIndexOfArchive(arcid: String): Int {
        val rowId = tankCardIdFor(arcid) ?: arcid
        return _downloadList.value.indexOfFirst { it.arcid == rowId }
    }

    /** The label whose list shows [arcid]: a tank's card, and so its members, are on the default list. */
    fun labelListingArchive(arcid: String, label: String?): String? =
        if (downloadManager.tankGroupFor(arcid) != null) null else label

    /**
     * Adapter position for a full-list [index], or null when pagination is on
     * and the index is not on the currently-displayed page. With pagination,
     * [listIndexInPage] is a bare modulo — an off-page index collides with a
     * visible row's adapter position, so a notify keyed by it rebinds the
     * wrong row. Only an on-page index round-trips back to itself through
     * [positionInList] (identity when not paginating). Single home for this
     * page-membership rule; use it before any notifyItem* keyed by a
     * full-list index.
     */
    fun adapterPositionForListIndex(index: Int): Int? {
        val inPage = listIndexInPage(index)
        return if (positionInList(inPage) == index) inPage else null
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
     * Deletes a range of downloads. If [deleteFiles] is true, also removes
     * the download directories and dirname DB records
     * ([DownloadManager.deleteRangeDownloadAndFiles]).
     */
    fun deleteRangeDownloads(
        downloadInfoList: List<DownloadInfo>,
        arcidList: List<String>,
        deleteFiles: Boolean,
        tankCardIds: Collection<String> = emptyList(),
    ) {
        // Checked tank cards: their group rows go with the member rows.
        downloadManager.deleteRangeDownloadAndFiles(downloadInfoList, arcidList, deleteFiles, tankCardIds)
    }

    /**
     * Moves downloads to a new label.
     */
    fun moveDownloads(downloadInfoList: List<DownloadInfo>, label: String?) {
        downloadManager.changeLabel(downloadInfoList, label)
    }

    /** Stops every running and queued download (FAB "stop all"). */
    fun stopAllDownloads() {
        downloadManager.stopAllDownload()
    }

    /** Resets the reading progress of every download (local, snapshot and server). */
    fun resetAllReadingProgress() {
        downloadManager.resetAllReadingProgress()
    }

    /**
     * Stops a range of downloads by arcid list.
     */
    fun stopRangeDownloads(arcidList: List<String>) {
        downloadManager.stopRangeDownload(arcidList)
    }

    // -------------------------------------------------------------------------
    // Archive import (moved from DownloadImportHelper)
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // DownloadInfoListener implementation → sealed event emission
    // -------------------------------------------------------------------------

    override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
        downloadDirCache.invalidate(oldInfo.arcid)
        downloadDirCache.invalidate(newInfo.arcid)
        _downloadEvent.tryEmit(DownloadUiEvent.Replaced(newInfo, oldInfo))
    }

    override fun onChange() {
        _downloadEvent.tryEmit(DownloadUiEvent.LabelDeleted)
    }

    override fun onRenameLabel(from: String, to: String) {
        _downloadEvent.tryEmit(DownloadUiEvent.LabelRenamed(from, to))
    }

    override fun onUpdateLabels() {
        _downloadEvent.tryEmit(DownloadUiEvent.LabelsChanged)
    }

    companion object {
        /** The row count from which pagination activates. */
        private const val PAGINATION_SIZE = 500

        /** Available per-page count choices for the pagination indicator. */
        private val PER_PAGE_COUNT_CHOICES = intArrayOf(50, 100, 200, 300, 500)
    }
}
