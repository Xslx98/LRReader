package com.lanraragi.reader.ui.scene.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.dao.HistoryInfo
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.dao.toArchive
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.reading.ReadingHistoryService
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.ui.list.ListScreenState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for [HistoryScene]. Holds the history screen as one
 * [ListScreenState] of [HistoryRow]s and runs the clear/delete/rating edits,
 * which [ReadingHistoryService] finishes even if the screen closes. The
 * Scene renders the state and diffs the rows into its adapter
 * ([com.lanraragi.reader.ui.list.ListDiffBinder]); views, adapters and
 * navigation remain in the Scene.
 */
class HistoryViewModel(
    private val historyEdits: ReadingHistoryService = ReadingHistoryService.default,
) : ViewModel() {

    private val historyRepository: HistoryRepository =
        ServiceRegistry.dataModule.historyRepository

    // -------------------------------------------------------------------------
    // Screen state
    // -------------------------------------------------------------------------

    /**
     * One history entry: the Room entity (for delete / navigation) and the
     * [Archive] it displays, kept together instead of two lists matched by
     * index.
     */
    data class HistoryRow(val info: HistoryInfo, val archive: Archive)

    private val _state = MutableStateFlow<ListScreenState<HistoryRow>>(ListScreenState.Loading)

    /** What the screen shows: the history rows, or loading / empty / the last load's error. */
    val state: StateFlow<ListScreenState<HistoryRow>> = _state.asStateFlow()

    private fun on(event: ListScreenState.Event<HistoryRow>) {
        _state.update { ListScreenState.reduce(it, event) }
    }

    // -------------------------------------------------------------------------
    // Data loading
    // -------------------------------------------------------------------------

    /**
     * Loads the history from the database on IO into the [state]. A reload
     * over shown rows keeps them; a failure keeps them too, and with none
     * shown it is the screen's error (it used to be logged and dropped).
     *
     * SwipeResultActionClear (swipe-to-dismiss) calls into this same path
     * after the async DB delete: the Scene's diff of the new rows removes
     * the swiped row in step with the SwipeDismissItemAnimator.
     */
    fun loadHistory() {
        on(ListScreenState.Event.Load)
        viewModelScope.launch {
            try {
                val lazyList = withContext(Dispatchers.IO) { historyRepository.getHistoryLazyList() }
                on(ListScreenState.Event.Loaded(lazyList.map { HistoryRow(it, it.toArchive()) }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load history", e)
                on(ListScreenState.Event.Failed(friendlyError(ServiceRegistry.appModule.getContext(), e)))
            }
        }
    }

    // -------------------------------------------------------------------------
    // In-place update (for detail-page rating propagation)
    // -------------------------------------------------------------------------

    /**
     * Sets the rating of the row showing [arcid] (a rating saved on the
     * detail page): the [state] changes at once (the Scene's diff rebinds the
     * row), and the rating is persisted so a reload keeps it.
     */
    fun updateRating(arcid: String, newRating: Float) {
        val current = _state.value.items
        val position = current.indexOfFirst { it.archive.arcid == arcid }
        if (position >= 0) {
            val row = current[position]
            val updated = current.toMutableList()
            updated[position] = row.copy(archive = row.archive.copy(rating = newRating))
            on(ListScreenState.Event.Loaded(updated))
            // Persist to archive_json so the rating survives a reload/restart;
            // updating only the in-memory list would revert on the next
            // loadHistory().
            viewModelScope.launch {
                try {
                    historyEdits.updateRating(row.info.arcid, row.info.serverProfileId, newRating)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist history rating", e)
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
                historyEdits.delete(info)
                loadHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete history item", e)
            }
        }
    }

    private val _batchRemoveDone = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 1)

    /** Emits `(succeeded, failed)` once a [removeHistories] run completes. */
    val batchRemoveDone: SharedFlow<Pair<Int, Int>> = _batchRemoveDone.asSharedFlow()

    /**
     * Removes the given archives from history (multi-select batch), then
     * reloads the list and reports the tally on [batchRemoveDone]. Each entry
     * is removed independently so one failure does not abort the rest.
     */
    fun removeHistories(archives: List<Archive>) {
        viewModelScope.launch {
            val tally = historyEdits.remove(archives)
            loadHistory()
            _batchRemoveDone.tryEmit(tally)
        }
    }

    /**
     * Clears the shown (active server's) history entries, then reloads the list.
     */
    fun clearAllHistory() {
        viewModelScope.launch {
            try {
                historyEdits.clearShownServer()
                loadHistory()
            } catch (e: CancellationException) {
                throw e
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
     * DiffUtil callback for the Scene's [HistoryRow] lists. Identity is
     * [Archive.arcid]; content compares the fields rendered in onBindViewHolder.
     */
    class RowDiffCallback(
        private val oldList: List<HistoryRow>,
        private val newList: List<HistoryRow>,
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition].archive.arcid == newList[newItemPosition].archive.arcid

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val o = oldList[oldItemPosition].archive
            val n = newList[newItemPosition].archive
            return o.title == n.title &&
                o.rating == n.rating &&
                o.thumbnailUrl == n.thumbnailUrl
        }
    }
}
