package com.hippo.ehviewer.ui.scene.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.dao.HistoryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for [HistoryScene]. Manages the history list data, DiffUtil
 * snapshot tracking, and clear/delete operations.
 *
 * The Scene observes [historyList] for data and [listUpdate] for dispatching
 * adapter updates. View references, adapters, and navigation remain in the Scene.
 */
class HistoryViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // History list state
    // -------------------------------------------------------------------------

    private val _historyList = MutableStateFlow<List<HistoryInfo>>(emptyList())

    /** The current history list. The Scene's adapter reads from this. */
    val historyList: StateFlow<List<HistoryInfo>> = _historyList.asStateFlow()

    /**
     * Snapshot of the list last dispatched to the adapter. Used to compute
     * DiffUtil deltas against the freshly loaded list. See
     * docs/diffutil-root-cause-analysis.md for why we are careful about
     * snapshot ownership.
     */
    private var lastSnapshot: List<HistoryInfo> = emptyList()

    // -------------------------------------------------------------------------
    // One-shot events
    // -------------------------------------------------------------------------

    /**
     * Carries a [DiffResult] plus the new list for the Scene to dispatch to
     * its adapter. Using a data class so the Scene can apply both atomically.
     */
    data class ListUpdate(
        val newList: List<HistoryInfo>,
        val diffResult: DiffUtil.DiffResult
    )

    private val _listUpdate = MutableSharedFlow<ListUpdate>(extraBufferCapacity = 1)

    /** Emitted after each load with the DiffResult for the adapter. */
    val listUpdate: SharedFlow<ListUpdate> = _listUpdate.asSharedFlow()

    // -------------------------------------------------------------------------
    // Data loading
    // -------------------------------------------------------------------------

    /**
     * Asynchronously loads the history list from the database on IO, computes a
     * DiffUtil delta against the last snapshot, and emits the result.
     *
     * SwipeResultActionClear (swipe-to-dismiss) calls into this same path after
     * async DB delete completes; the diff-driven remove of the swiped item
     * aligns with the SwipeDismissItemAnimator's ongoing animation since we
     * keep [lastSnapshot] in sync with what the adapter last saw.
     */
    fun loadHistory() {
        viewModelScope.launch {
            val lazyList = withContext(Dispatchers.IO) { EhDB.getHistoryLazyListAsync() }
            val newList = ArrayList(lazyList)
            val diff = DiffUtil.calculateDiff(
                HistoryInfoDiffCallback(lastSnapshot, newList)
            )
            _historyList.value = newList
            lastSnapshot = newList
            _listUpdate.tryEmit(ListUpdate(newList, diff))
        }
    }

    /**
     * Resets the snapshot so the next [loadHistory] starts from an empty
     * baseline and produces a clean inserts-only delta. Called when the
     * Scene's view is destroyed.
     */
    fun resetSnapshot() {
        lastSnapshot = emptyList()
        _historyList.value = emptyList()
    }

    // -------------------------------------------------------------------------
    // Delete operations
    // -------------------------------------------------------------------------

    /**
     * Deletes a single history entry by swiping, then reloads the list.
     */
    fun deleteHistoryItem(info: HistoryInfo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { EhDB.deleteHistoryInfoAsync(info) }
            loadHistory()
        }
    }

    /**
     * Clears all history entries, then reloads the list.
     */
    fun clearAllHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { EhDB.clearHistoryInfoAsync() }
            loadHistory()
        }
    }

    // -------------------------------------------------------------------------
    // DiffUtil callback
    // -------------------------------------------------------------------------

    /**
     * DiffUtil callback for HistoryInfo lists. Identity is `gid` (Room PK).
     * Content compares all fields rendered in onBindViewHolder so a metadata
     * refresh repaints the affected rows.
     */
    private class HistoryInfoDiffCallback(
        private val oldList: List<HistoryInfo>,
        private val newList: List<HistoryInfo>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].gid == newList[newItemPosition].gid
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val o = oldList[oldItemPosition]
            val n = newList[newItemPosition]
            return o.title == n.title &&
                o.uploader == n.uploader &&
                o.rating == n.rating &&
                o.category == n.category &&
                o.posted == n.posted &&
                o.simpleLanguage == n.simpleLanguage &&
                o.thumb == n.thumb
        }
    }
}
