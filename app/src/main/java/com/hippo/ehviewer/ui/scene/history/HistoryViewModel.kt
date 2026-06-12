package com.hippo.ehviewer.ui.scene.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.HistoryInfo
import com.hippo.ehviewer.dao.HistoryRepository
import com.hippo.ehviewer.mapper.toArchive
import com.lanraragi.reader.domain.Archive
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

    private val historyRepository: HistoryRepository =
        ServiceRegistry.dataModule.historyRepository

    // -------------------------------------------------------------------------
    // History list state
    // -------------------------------------------------------------------------

    private val _historyList = MutableStateFlow<List<Archive>>(emptyList())

    /** The current history list as [Archive] domain models for display. */
    val historyList: StateFlow<List<Archive>> = _historyList.asStateFlow()

    /**
     * Raw [HistoryInfo] entities from the last DB load, kept in parallel with
     * [_historyList] for operations that need the Room entity (delete, navigate).
     * Same ordering and indices as [_historyList].
     */
    private var rawHistoryList: List<HistoryInfo> = emptyList()

    /**
     * Snapshot of the list last dispatched to the adapter. Used to compute
     * DiffUtil deltas against the freshly loaded list. See
     * docs/diffutil-root-cause-analysis.md for why we are careful about
     * snapshot ownership.
     */
    private var lastSnapshot: List<Archive> = emptyList()

    // -------------------------------------------------------------------------
    // One-shot events
    // -------------------------------------------------------------------------

    /**
     * Carries a [DiffResult] plus the new list for the Scene to dispatch to
     * its adapter. Using a data class so the Scene can apply both atomically.
     */
    data class ListUpdate(
        val newList: List<Archive>,
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
            try {
                val lazyList = withContext(Dispatchers.IO) { historyRepository.getHistoryLazyList() }
                rawHistoryList = ArrayList(lazyList)
                val newList = ArrayList(lazyList.map { it.toArchive() })
                val diff = DiffUtil.calculateDiff(
                    ArchiveDiffCallback(lastSnapshot, newList)
                )
                _historyList.value = newList
                lastSnapshot = newList
                _listUpdate.tryEmit(ListUpdate(newList, diff))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load history", e)
            }
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
        rawHistoryList = emptyList()
    }

    // -------------------------------------------------------------------------
    // In-place update (for onSceneResult rating propagation)
    // -------------------------------------------------------------------------

    /**
     * Update the rating of the Archive at [position] in the display list.
     * Used by HistoryScene.onSceneResult for immediate UI feedback.
     */
    fun updateRatingAtPosition(position: Int, newRating: Float) {
        val current = _historyList.value.toMutableList()
        if (position in current.indices) {
            current[position] = current[position].copy(rating = newRating)
            _historyList.value = current
            lastSnapshot = current
            // Persist to archive_json so the rating survives a reload/restart;
            // updating only the in-memory list would revert on the next
            // loadHistory(). arcid comes from the parallel raw list.
            val arcid = rawHistoryList.getOrNull(position)?.arcid
            if (arcid != null) {
                viewModelScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            historyRepository.updateRating(arcid, newRating)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to persist history rating", e)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Delete operations
    // -------------------------------------------------------------------------

    /**
     * Deletes a single history entry by swiping, then reloads the list.
     */
    fun deleteHistoryItem(info: HistoryInfo) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { historyRepository.deleteHistoryInfo(info) }
                loadHistory()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete history item", e)
            }
        }
    }

    /**
     * Returns the raw [HistoryInfo] at the given position (same index as [historyList]).
     * Used by the Scene for operations that require the Room entity (IPC, delete, download).
     */
    fun getRawHistoryInfo(position: Int): HistoryInfo? {
        return rawHistoryList.getOrNull(position)
    }

    /**
     * Clears all history entries, then reloads the list.
     */
    fun clearAllHistory() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { historyRepository.clearHistory() }
                loadHistory()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear history", e)
            }
        }
    }

    companion object {
        private const val TAG = "HistoryViewModel"
    }

    // -------------------------------------------------------------------------
    // DiffUtil callback
    // -------------------------------------------------------------------------

    /**
     * DiffUtil callback for [Archive] lists. Identity is [Archive.arcid].
     * Content compares fields rendered in onBindViewHolder.
     */
    private class ArchiveDiffCallback(
        private val oldList: List<Archive>,
        private val newList: List<Archive>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].arcid == newList[newItemPosition].arcid
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val o = oldList[oldItemPosition]
            val n = newList[newItemPosition]
            return o.title == n.title &&
                o.rating == n.rating &&
                o.thumbnailUrl == n.thumbnailUrl
        }
    }
}
