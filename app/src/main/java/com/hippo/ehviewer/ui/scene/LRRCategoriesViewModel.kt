package com.hippo.ehviewer.ui.scene

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.data.LRRCategory
import com.lanraragi.reader.client.api.friendlyError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for [LRRCategoriesScene]. Manages category list state and
 * delegates all LANraragi category API calls (load/create/edit/delete).
 *
 * The Scene observes [categories] for list updates and [uiEvent] for
 * one-shot Toast messages. View construction, adapter, dialogs, and
 * navigation remain in the Scene.
 */
class LRRCategoriesViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // Category list state
    // -------------------------------------------------------------------------

    private val _categories = MutableStateFlow<List<LRRCategory>>(emptyList())

    /** Sorted category list (pinned first, then by name). */
    val categories: StateFlow<List<LRRCategory>> = _categories.asStateFlow()

    // -------------------------------------------------------------------------
    // Loading state
    // -------------------------------------------------------------------------

    private val _isLoading = MutableStateFlow(false)

    /** Whether a load/CRUD operation is in progress. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
        data class ShowError(val message: String) : CategoriesUiEvent
        data class ShowSuccess(val messageResId: Int) : CategoriesUiEvent
    }

    // -------------------------------------------------------------------------
    // API operations
    // -------------------------------------------------------------------------

    /**
     * Fetches all categories from LANraragi, sorts them (pinned first),
     * and emits the result to [_categories]. Emits [CategoriesUiEvent.ShowError]
     * on failure.
     */
    fun loadCategories() {
        _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                val categories = LRRCategoryApi.getCategories(client, serverUrl)

                // Sort: pinned first, then unpinned; skip nameless entries
                val pinned = mutableListOf<LRRCategory>()
                val unpinned = mutableListOf<LRRCategory>()
                for (cat in categories) {
                    if (cat.name.isNullOrEmpty()) continue
                    if (cat.isPinned()) {
                        pinned.add(cat)
                    } else {
                        unpinned.add(cat)
                    }
                }
                pinned.addAll(unpinned)

                _categories.value = ArrayList(pinned)
                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load categories", e)
                _isLoading.value = false
                val context = ServiceRegistry.appModule.getContext()
                _uiEvent.tryEmit(CategoriesUiEvent.ShowError(friendlyError(context, e)))
            }
        }
    }

    /**
     * Creates a new category on the server, then reloads the list.
     */
    fun createCategory(name: String, search: String?, pinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                LRRCategoryApi.createCategory(client, serverUrl, name, search, pinned)

                _uiEvent.tryEmit(
                    CategoriesUiEvent.ShowSuccess(com.hippo.ehviewer.R.string.lrr_category_created)
                )
                loadCategoriesInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create category", e)
                val context = ServiceRegistry.appModule.getContext()
                _uiEvent.tryEmit(CategoriesUiEvent.ShowError(friendlyError(context, e)))
            }
        }
    }

    /**
     * Updates an existing category on the server, then reloads the list.
     */
    fun editCategory(categoryId: String, name: String, search: String?, pinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                LRRCategoryApi.updateCategory(client, serverUrl, categoryId, name, search, pinned)

                _uiEvent.tryEmit(
                    CategoriesUiEvent.ShowSuccess(com.hippo.ehviewer.R.string.lrr_category_updated)
                )
                loadCategoriesInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update category", e)
                val context = ServiceRegistry.appModule.getContext()
                _uiEvent.tryEmit(CategoriesUiEvent.ShowError(friendlyError(context, e)))
            }
        }
    }

    /**
     * Deletes a category from the server, then reloads the list.
     */
    fun deleteCategory(categoryId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                LRRCategoryApi.deleteCategory(client, serverUrl, categoryId)

                _uiEvent.tryEmit(
                    CategoriesUiEvent.ShowSuccess(com.hippo.ehviewer.R.string.lrr_category_deleted)
                )
                loadCategoriesInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete category", e)
                val context = ServiceRegistry.appModule.getContext()
                _uiEvent.tryEmit(CategoriesUiEvent.ShowError(friendlyError(context, e)))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Internal reload that does not toggle [_isLoading] — used after
     * create/edit/delete where the loading indicator is not shown.
     */
    private suspend fun loadCategoriesInternal() {
        try {
            val serverUrl = LRRAuthManager.getServerUrl() ?: return
            val client = ServiceRegistry.networkModule.okHttpClient

            val categories = LRRCategoryApi.getCategories(client, serverUrl)

            val pinned = mutableListOf<LRRCategory>()
            val unpinned = mutableListOf<LRRCategory>()
            for (cat in categories) {
                if (cat.name.isNullOrEmpty()) continue
                if (cat.isPinned()) {
                    pinned.add(cat)
                } else {
                    unpinned.add(cat)
                }
            }
            pinned.addAll(unpinned)

            _categories.value = ArrayList(pinned)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload categories after CRUD", e)
            val context = ServiceRegistry.appModule.getContext()
            _uiEvent.tryEmit(CategoriesUiEvent.ShowError(friendlyError(context, e)))
        }
    }

    companion object {
        private const val TAG = "LRRCategoriesViewModel"
    }
}
