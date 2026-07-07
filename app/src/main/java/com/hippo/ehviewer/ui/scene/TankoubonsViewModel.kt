package com.hippo.ehviewer.ui.scene

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for [TankoubonsScene]. Manages the tankoubon list state and
 * delegates all LANraragi tankoubon API calls (load/create/rename/edit/delete).
 *
 * The Scene observes [tanks] for list updates and [uiEvent] for one-shot
 * Toast messages. View construction, adapter, dialogs, and navigation
 * remain in the Scene.
 */
class TankoubonsViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // Tankoubon list state
    // -------------------------------------------------------------------------

    private val _tanks = MutableStateFlow<List<LRRTankoubonApi.Tankoubon>>(emptyList())

    /** Tankoubon list in server order. */
    val tanks: StateFlow<List<LRRTankoubonApi.Tankoubon>> = _tanks.asStateFlow()

    // -------------------------------------------------------------------------
    // Loading state
    // -------------------------------------------------------------------------

    private val _isLoading = MutableStateFlow(false)

    /** Whether a load operation is in progress. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // -------------------------------------------------------------------------
    // One-shot UI events
    // -------------------------------------------------------------------------

    private val _uiEvent = MutableSharedFlow<TankUiEvent>(extraBufferCapacity = 8)

    /** One-shot events for Toast display (success/error messages). */
    val uiEvent: SharedFlow<TankUiEvent> = _uiEvent.asSharedFlow()

    /**
     * Sealed interface for one-shot UI events emitted by this ViewModel.
     * The Scene observes [uiEvent] and dispatches via `when`.
     */
    sealed interface TankUiEvent {
        data class ShowError(val message: String) : TankUiEvent
        data class ShowSuccess(val messageResId: Int) : TankUiEvent
    }

    // -------------------------------------------------------------------------
    // API operations
    // -------------------------------------------------------------------------

    /**
     * Fetches all tankoubons from LANraragi and emits the result to [_tanks].
     * The list endpoint is paginated by the server's page size: loop until we
     * hold `total` entries (safety cap generously above any realistic tank
     * count). Emits [TankUiEvent.ShowError] on failure.
     */
    fun loadTankoubons() {
        _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl()
                if (serverUrl == null) {
                    _isLoading.value = false // otherwise the scene spins forever
                    return@launch
                }

                _tanks.value = fetchAllTanks(serverUrl)
                _isLoading.value = false
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to load tankoubons", e)
                _isLoading.value = false
                val context = ServiceRegistry.appModule.getContext()
                _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
            }
        }
    }

    /**
     * Creates a new tankoubon on the server, then reloads the list.
     */
    fun create(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                LRRTankoubonApi.createTankoubon(client, serverUrl, name)

                _uiEvent.tryEmit(TankUiEvent.ShowSuccess(R.string.tank_op_done))
                loadTankoubonsInternal()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to create tankoubon", e)
                val context = ServiceRegistry.appModule.getContext()
                _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
            }
        }
    }

    /**
     * Renames an existing tankoubon on the server, then reloads the list.
     */
    fun rename(tankId: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                LRRTankoubonApi.renameTankoubon(client, serverUrl, tankId, name)

                _uiEvent.tryEmit(TankUiEvent.ShowSuccess(R.string.tank_op_done))
                loadTankoubonsInternal()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to rename tankoubon", e)
                val context = ServiceRegistry.appModule.getContext()
                _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
            }
        }
    }

    /**
     * Updates a tankoubon's summary/tags metadata, then reloads the list.
     * Membership is left untouched (archives = null).
     */
    fun editMeta(tankId: String, summary: String, tags: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                LRRTankoubonApi.updateTankoubon(client, serverUrl, tankId, summary = summary, tags = tags)

                _uiEvent.tryEmit(TankUiEvent.ShowSuccess(R.string.tank_op_done))
                loadTankoubonsInternal()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to update tankoubon metadata", e)
                val context = ServiceRegistry.appModule.getContext()
                _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
            }
        }
    }

    /**
     * Deletes a tankoubon from the server (never its archives), then reloads
     * the list.
     */
    fun delete(tankId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                LRRTankoubonApi.deleteTankoubon(client, serverUrl, tankId)

                _uiEvent.tryEmit(TankUiEvent.ShowSuccess(R.string.tank_op_done))
                loadTankoubonsInternal()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to delete tankoubon", e)
                val context = ServiceRegistry.appModule.getContext()
                _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Internal reload that does not toggle [_isLoading] — used after
     * create/rename/edit/delete where the loading indicator is not shown.
     */
    private suspend fun loadTankoubonsInternal() {
        try {
            val serverUrl = LRRAuthManager.getServerUrl() ?: return

            _tanks.value = fetchAllTanks(serverUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to reload tankoubons after CRUD", e)
            val context = ServiceRegistry.appModule.getContext()
            _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
        }
    }

    /**
     * Pages through GET /api/tankoubons until all `total` entries are held.
     * Server pages are 0-BASED (page 0 = parameterless request); a 1-based
     * loop starts on the empty second page and shows a blank list.
     */
    private suspend fun fetchAllTanks(serverUrl: String): List<LRRTankoubonApi.Tankoubon> {
        val client = ServiceRegistry.networkModule.okHttpClient
        val all = mutableListOf<LRRTankoubonApi.Tankoubon>()
        var page = 0
        while (page < MAX_PAGES) {
            val r = LRRTankoubonApi.getTankoubons(client, serverUrl, page)
            all.addAll(r.result)
            if (r.result.isEmpty() || all.size >= r.total) break
            page++
        }
        return ArrayList(all)
    }

    /**
     * 423 (locked) gets a dedicated message — the server is busy regenerating
     * the tank; everything else goes through the shared [friendlyError] map.
     */
    private fun errorMessage(context: Context, e: Exception): String =
        if (e is LRRHttpException && e.code == HTTP_LOCKED) {
            context.getString(R.string.tank_locked)
        } else {
            friendlyError(context, e)
        }

    private companion object {
        const val MAX_PAGES = 100
        const val HTTP_LOCKED = 423
        const val TAG = "TankoubonsViewModel"
    }
}
