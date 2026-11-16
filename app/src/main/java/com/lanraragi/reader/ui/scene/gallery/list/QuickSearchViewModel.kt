package com.lanraragi.reader.ui.scene.gallery.list

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.dao.QuickSearch
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.ui.list.ListScreenState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * ViewModel for [QuickSearchScene]. Manages the quick search list data and
 * delete/reorder operations.
 *
 * The gallery list's quick-search drawer ([BookmarksDraw]) uses its own
 * instance, scoped to its [GalleryListScene], for loading and adding entries
 * and to remember its scroll position.
 *
 * The Scene observes [state] for the current list and [uiEvent] for
 * one-shot events (errors, deletions). View references, adapters, drag-drop
 * setup, dialogs, and navigation remain in the Scene.
 */
class QuickSearchViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // Quick search list state
    // -------------------------------------------------------------------------

    private val _state = MutableStateFlow<ListScreenState<QuickSearch>>(ListScreenState.Loading)

    /**
     * What the screen shows: the quick searches (the Scene's adapter renders
     * [ListScreenState.items]), or loading / empty / the load's error.
     */
    val state: StateFlow<ListScreenState<QuickSearch>> = _state.asStateFlow()

    private val shown: List<QuickSearch> get() = _state.value.items

    private fun on(event: ListScreenState.Event<QuickSearch>) {
        _state.update { ListScreenState.reduce(it, event) }
    }

    // -------------------------------------------------------------------------
    // One-shot events
    // -------------------------------------------------------------------------

    sealed interface QuickSearchUiEvent {
        /** A failed delete or reorder (a failed load is the [state]'s error). */
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
     * Asynchronously loads the quick search list from the database on IO
     * into the [state]; a failure is the state's error.
     */
    fun loadQuickSearches() {
        on(ListScreenState.Event.Load)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { ServiceRegistry.dataModule.quickSearchRepository.getAll() }
                on(ListScreenState.Event.Loaded(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load quick searches", e)
                on(ListScreenState.Event.Failed(friendlyError(ServiceRegistry.appModule.getContext(), e)))
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
     * The delete finishes even if the screen closes (non-cancellable, like
     * [addQuickSearch]): this ViewModel is cleared when its scene is popped.
     */
    fun deleteQuickSearch(quickSearch: QuickSearch) {
        on(ListScreenState.Event.Loaded(shown.filter { it.id != quickSearch.id }))
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    ServiceRegistry.dataModule.quickSearchRepository.delete(quickSearch)
                }
                _uiEvent.tryEmit(QuickSearchUiEvent.Deleted(quickSearch))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete quick search", e)
                _uiEvent.tryEmit(QuickSearchUiEvent.ShowError(friendlyError(ServiceRegistry.appModule.getContext(), e)))
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
     * persists the new ordering in the database. The write finishes even if
     * the screen closes right after the drag (non-cancellable).
     */
    fun moveQuickSearch(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return

        // Update in-memory list immediately for responsive UI
        val currentList = shown.toMutableList()
        if (fromPosition < 0 || fromPosition >= currentList.size ||
            toPosition < 0 || toPosition >= currentList.size
        ) {
            return
        }
        val item = currentList.removeAt(fromPosition)
        currentList.add(toPosition, item)
        on(ListScreenState.Event.Loaded(currentList))

        // Persist reorder to database
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    ServiceRegistry.dataModule.quickSearchRepository.move(fromPosition, toPosition)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move quick search", e)
                _uiEvent.tryEmit(QuickSearchUiEvent.ShowError(friendlyError(ServiceRegistry.appModule.getContext(), e)))
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
