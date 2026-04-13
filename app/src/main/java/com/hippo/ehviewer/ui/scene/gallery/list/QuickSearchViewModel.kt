package com.hippo.ehviewer.ui.scene.gallery.list

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.dao.QuickSearch
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
 * ViewModel for [QuickSearchScene]. Manages the quick search list data and
 * delete/reorder operations.
 *
 * The Scene observes [quickSearches] for the current list and [uiEvent] for
 * one-shot events (errors, deletions). View references, adapters, drag-drop
 * setup, dialogs, and navigation remain in the Scene.
 */
class QuickSearchViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // Quick search list state
    // -------------------------------------------------------------------------

    private val _quickSearches = MutableStateFlow<List<QuickSearch>>(emptyList())

    /** The current quick search list. The Scene's adapter reads from this. */
    val quickSearches: StateFlow<List<QuickSearch>> = _quickSearches.asStateFlow()

    // -------------------------------------------------------------------------
    // One-shot events
    // -------------------------------------------------------------------------

    sealed interface QuickSearchUiEvent {
        data class ShowError(val message: String) : QuickSearchUiEvent
        data class Deleted(val quickSearch: QuickSearch) : QuickSearchUiEvent
    }

    private val _uiEvent = MutableSharedFlow<QuickSearchUiEvent>(extraBufferCapacity = 1)

    /** Emitted for one-shot UI events (error messages, deletion confirmations). */
    val uiEvent: SharedFlow<QuickSearchUiEvent> = _uiEvent.asSharedFlow()

    // -------------------------------------------------------------------------
    // Data loading
    // -------------------------------------------------------------------------

    /**
     * Asynchronously loads the quick search list from the database on IO and
     * emits the result to [quickSearches].
     */
    fun loadQuickSearches() {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { EhDB.getAllQuickSearchAsync() }
                _quickSearches.value = result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load quick searches", e)
                _uiEvent.tryEmit(
                    QuickSearchUiEvent.ShowError("Failed to load quick searches")
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Delete operation
    // -------------------------------------------------------------------------

    /**
     * Deletes a quick search entry from the database and removes it from the
     * in-memory list.
     */
    fun deleteQuickSearch(quickSearch: QuickSearch) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { EhDB.deleteQuickSearchAsync(quickSearch) }
                _quickSearches.value = _quickSearches.value.filter { it.id != quickSearch.id }
                _uiEvent.tryEmit(QuickSearchUiEvent.Deleted(quickSearch))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete quick search", e)
                _uiEvent.tryEmit(
                    QuickSearchUiEvent.ShowError("Failed to delete quick search")
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reorder operation
    // -------------------------------------------------------------------------

    /**
     * Moves a quick search item from [fromPosition] to [toPosition] and
     * persists the new ordering in the database.
     */
    fun moveQuickSearch(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return

        // Update in-memory list immediately for responsive UI
        val currentList = _quickSearches.value.toMutableList()
        if (fromPosition < 0 || fromPosition >= currentList.size ||
            toPosition < 0 || toPosition >= currentList.size
        ) {
            return
        }
        val item = currentList.removeAt(fromPosition)
        currentList.add(toPosition, item)
        _quickSearches.value = currentList

        // Persist reorder to database
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    EhDB.moveQuickSearchAsync(fromPosition, toPosition)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move quick search", e)
                _uiEvent.tryEmit(
                    QuickSearchUiEvent.ShowError("Failed to reorder quick search")
                )
            }
        }
    }

    companion object {
        private const val TAG = "QuickSearchViewModel"
    }
}
