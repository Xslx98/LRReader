package com.lanraragi.reader.ui.scene.gallery.list

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.dao.QuickSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * The gallery list's quick-search drawer ([BookmarksDraw]) uses its own
 * instance, scoped to its [GalleryListScene], for loading and adding entries
 * and to remember its scroll position.
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
                val result = withContext(Dispatchers.IO) { ServiceRegistry.dataModule.quickSearchRepository.getAll() }
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
     * Deletes a quick search entry. The in-memory list is updated
     * synchronously (optimistically, like [moveQuickSearch]); the Scene's
     * DiffUtil collector turns the emission into the removal notification.
     * On DB failure the list is reloaded from Room so the optimistic removal
     * is rolled back and the UI stays consistent with what actually persisted.
     */
    fun deleteQuickSearch(quickSearch: QuickSearch) {
        _quickSearches.value = _quickSearches.value.filter { it.id != quickSearch.id }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ServiceRegistry.dataModule.quickSearchRepository.delete(quickSearch) }
                _uiEvent.tryEmit(QuickSearchUiEvent.Deleted(quickSearch))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete quick search", e)
                _uiEvent.tryEmit(
                    QuickSearchUiEvent.ShowError("Failed to delete quick search")
                )
                // Reconcile: the row is still in the DB — reload so it
                // reappears instead of leaving UI and DB diverged.
                loadQuickSearches()
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
                    ServiceRegistry.dataModule.quickSearchRepository.move(fromPosition, toPosition)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move quick search", e)
                _uiEvent.tryEmit(
                    QuickSearchUiEvent.ShowError("Failed to reorder quick search")
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gallery-list drawer (BookmarksDraw)
    // -------------------------------------------------------------------------

    /** First visible row of the drawer's list, restored when the drawer view comes back. */
    var drawerScrollPosition: Int? = null

    /**
     * Loads the quick searches for the drawer. With [showTranslations] the
     * entries whose names [translate] changes are renamed and persisted
     * ([QuickSearchNameReconciler]). [onLoaded] gets the list on the main
     * thread; it is not called when loading fails (the drawer stays empty).
     */
    fun loadForDrawer(
        showTranslations: Boolean,
        translate: (Array<String>) -> String,
        onLoaded: (List<QuickSearch>) -> Unit,
    ) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                val repository = ServiceRegistry.dataModule.quickSearchRepository
                val all = try {
                    repository.getAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load quick searches for the drawer", e)
                    return@withContext null
                }
                val toUpdate = QuickSearchNameReconciler.reconcile(all, showTranslations, translate)
                if (toUpdate.isNotEmpty()) {
                    try {
                        withContext(NonCancellable) { for (qs in toUpdate) repository.update(qs) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Non-fatal: the list still shows the translated names
                        // in memory even if persisting them failed.
                        Log.e(TAG, "Failed to persist quick-search tag translations", e)
                    }
                }
                all
            } ?: return@launch
            onLoaded(list)
        }
    }

    /**
     * Saves a new quick search from the drawer's add dialog. The insert
     * finishes even if the screen closes; [onAdded] runs on the main thread
     * only when it succeeded, so the drawer list matches what persisted.
     */
    fun addQuickSearch(quickSearch: QuickSearch, onAdded: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    ServiceRegistry.dataModule.quickSearchRepository.insert(quickSearch)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add quick search", e)
                return@launch
            }
            onAdded()
        }
    }

    companion object {
        private const val TAG = "QuickSearchViewModel"
    }
}
