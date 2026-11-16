package com.lanraragi.reader.ui.scene

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.category.CategoryLibraryService
import com.lanraragi.reader.domain.Category
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

/**
 * ViewModel for [LRRCategoriesScene]. Manages category list state and
 * calls [CategoryLibraryService] for load/create/edit/delete.
 *
 * The Scene observes [state] for what the list shows and [uiEvent] for
 * one-shot Toast messages. View construction, adapter, dialogs, and
 * navigation remain in the Scene.
 */
class LRRCategoriesViewModel(
    /**
     * Test seam for the quick-search category-name back-fill (spec 2026-09-15,
     * Q4). The default reaches [ServiceRegistry] lazily, at call time, so the
     * no-arg construction the Scene's ViewModelProvider uses never touches the
     * registry — and tests that don't care (no data module installed) still
     * construct the VM without pinning this parameter.
     */
    private val categoryNameSync: suspend (List<Category>) -> Unit = { categories ->
        ServiceRegistry.dataModule.quickSearchRepository.syncCategoryNames(categories.map { it.id to it.name })
    },
    private val library: CategoryLibraryService = CategoryLibraryService.default,
) : ViewModel() {

    // -------------------------------------------------------------------------
    // Screen state
    // -------------------------------------------------------------------------

    private val _state = MutableStateFlow<ListScreenState<Category>>(ListScreenState.Loading)

    /**
     * What the screen shows: the sorted categories (pinned first, then by
     * name), or loading / empty / the last load's error. Survives rotation.
     */
    val state: StateFlow<ListScreenState<Category>> = _state.asStateFlow()

    private fun on(event: ListScreenState.Event<Category>) {
        _state.update { ListScreenState.reduce(it, event) }
    }

    // -------------------------------------------------------------------------
    // One-shot UI events
    // -------------------------------------------------------------------------

    private val _uiEvent = MutableSharedFlow<CategoriesUiEvent>(extraBufferCapacity = 8)

    /** One-shot events for Toast display (success/error messages). */
    val uiEvent: SharedFlow<CategoriesUiEvent> = _uiEvent.asSharedFlow()

    /**
     * Sealed interface for one-shot UI events emitted by this ViewModel.
     * The Scene observes [uiEvent] and dispatches via `when`.
     */
    sealed interface CategoriesUiEvent {
        /** A failure the screen does not show by itself (a write, or a reload while categories are shown). */
        data class ShowError(val message: String) : CategoriesUiEvent
        data class ShowSuccess(val messageResId: Int) : CategoriesUiEvent
    }

    // -------------------------------------------------------------------------
    // API operations
    // -------------------------------------------------------------------------

    /**
     * Fetches all categories from LANraragi, sorts them (pinned first), and
     * publishes them as the [state]; a failure becomes the state's error
     * (shown as an error view, or as a toast while categories are shown).
     */
    fun loadCategories() {
        on(ListScreenState.Event.Load)
        viewModelScope.launch(Dispatchers.IO) { reload() }
    }

    /**
     * Creates a new category on the server, then reloads the list.
     */
    fun createCategory(name: String, search: String?, pinned: Boolean) =
        writeThenReload(R.string.lrr_category_created) { library.create(name, search, pinned) }

    /**
     * Updates an existing category on the server, then reloads the list.
     */
    fun editCategory(categoryId: String, name: String, search: String?, pinned: Boolean) =
        writeThenReload(R.string.lrr_category_updated) { library.update(categoryId, name, search, pinned) }

    /**
     * Deletes a category from the server, then reloads the list.
     */
    fun deleteCategory(categoryId: String) =
        writeThenReload(R.string.lrr_category_deleted) { library.delete(categoryId) }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Runs [write] (which the service finishes even if this screen closes),
     * then reports [successRes] and reloads. Nothing happens when no server is
     * configured; a failure is reported as [CategoriesUiEvent.ShowError].
     */
    private fun writeThenReload(@StringRes successRes: Int, write: suspend () -> Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!write()) return@launch
                _uiEvent.tryEmit(CategoriesUiEvent.ShowSuccess(successRes))
                reload()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emitError(e)
            }
        }
    }

    /**
     * Loads the categories into the [state]. No server configured (null)
     * answers an empty list, so the screen does not spin forever. A failure
     * while categories are shown keeps them and is reported as a toast.
     */
    private suspend fun reload() {
        try {
            val categories = library.load().orEmpty()
            on(ListScreenState.Event.Loaded(ArrayList(categories)))
            syncNames(categories)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to load categories", e)
            val message = friendlyError(ServiceRegistry.appModule.getContext(), e)
            on(ListScreenState.Event.Failed(message))
            if (_state.value.items.isNotEmpty()) _uiEvent.tryEmit(CategoriesUiEvent.ShowError(message))
        }
    }

    private fun emitError(e: Exception) {
        val context = ServiceRegistry.appModule.getContext()
        _uiEvent.tryEmit(CategoriesUiEvent.ShowError(friendlyError(context, e)))
    }

    /**
     * Back-fill / refresh quick-search category names from a freshly loaded
     * list. Display-only data: a failure must never surface as a categories
     * error, so it is logged and swallowed (cancellation still propagates).
     */
    private suspend fun syncNames(categories: List<Category>) {
        try {
            categoryNameSync(categories)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Literal message only: a template with property access would defeat the R8 Log strip.
            Log.w(TAG, "Quick-search category name sync failed")
        }
    }

    companion object {
        private const val TAG = "LRRCategoriesViewModel"
    }
}
