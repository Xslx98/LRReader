package com.lanraragi.reader.ui.scene

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.gallery.TankSessionRouter
import com.lanraragi.reader.ui.TankMembershipSyncFactory
import com.lanraragi.reader.ui.scene.gallery.list.TankListService
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

    private val _coverFallbacks = MutableStateFlow<Map<String, TankListService.CoverFallback>>(emptyMap())

    /**
     * tankId → first member's cover, for tanks the cover probe reported
     * WITHOUT a generated cover (probe 202; the probe itself queues
     * server-side generation). Published before [tanks] on each load so
     * cover binds already see it.
     */
    val coverFallbacks: StateFlow<Map<String, TankListService.CoverFallback>> = _coverFallbacks.asStateFlow()

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

        /** Drawer row click resolved into a whole-tank reader session (spec 2026-09-21 §7). */
        data class OpenReader(val intent: Intent) : TankUiEvent

        /**
         * Long-press "download" resolved the tank's current membership; the
         * scene dispatches the fill on the main thread (download-manager
         * state lookups are main-thread only).
         */
        data class FillTank(
            val tankId: String,
            val name: String,
            val members: List<Archive>,
            val memberIdsInOrder: List<String>,
        ) : TankUiEvent

        /**
         * Long-press auto-sort persisted a new order; [previousOrder] is
         * what [restoreOrder] should PUT back on undo.
         */
        data class Sorted(val tankId: String, val previousOrder: List<String>) : TankUiEvent
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
    // ── Drawer click = read / long-press download (spec 2026-09-21 §7) ──

    private val _openingTankId = MutableStateFlow<String?>(null)

    /** Tank whose row shows an inline spinner while its session is being built. */
    val openingTankId: StateFlow<String?> = _openingTankId.asStateFlow()

    /** Session-builder seam (production = [TankSessionRouter.buildResumeIntent]). */
    internal var resumeIntentBuilder: suspend (Context, String, Long) -> Intent =
        { ctx, tankId, profileId -> TankSessionRouter.buildResumeIntent(ctx, tankId, profileId) }

    /**
     * Row click: rebuild the whole-tank session from server truth and hand
     * the intent to the scene (server progress > 1 resumes there, else page
     * 1). One build at a time; failure surfaces as [TankUiEvent.ShowError].
     */
    fun openTank(tank: LRRTankoubonApi.Tankoubon) {
        if (_openingTankId.value != null) return
        _openingTankId.value = tank.id
        val context = ServiceRegistry.appModule.getContext()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val intent = resumeIntentBuilder(context, tank.id, LRRAuthManager.getActiveProfileId())
                _uiEvent.tryEmit(TankUiEvent.OpenReader(intent))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
            } finally {
                _openingTankId.value = null
            }
        }
    }

    /**
     * Long-press auto-sort (spec 2026-09-21 §3): fetch the current
     * membership, sort it by the episode-aware title key and PUT the whole
     * order. Already sorted → [R.string.tank_already_sorted]; success →
     * [TankUiEvent.Sorted] (undo) and a list reload so group rows follow.
     */
    fun autoSort(tank: LRRTankoubonApi.Tankoubon) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ServiceRegistry.appModule.getContext()
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val previous = service.autoSort(serverUrl, tank.id)
                if (previous == null) {
                    _uiEvent.tryEmit(TankUiEvent.ShowSuccess(R.string.tank_already_sorted))
                    return@launch
                }
                _uiEvent.tryEmit(TankUiEvent.Sorted(tank.id, previous))
                loadTankoubonsInternal()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
            }
        }
    }

    /** Undo of [autoSort]: PUT [previousOrder] back, then reload. */
    fun restoreOrder(tankId: String, previousOrder: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ServiceRegistry.appModule.getContext()
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                service.restoreOrder(serverUrl, tankId, previousOrder)
                _uiEvent.tryEmit(TankUiEvent.ShowSuccess(R.string.tank_op_done))
                loadTankoubonsInternal()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
            }
        }
    }

    /** Long-press "download": fetch current membership, then let the scene run the fill. */
    fun fillTank(tank: LRRTankoubonApi.Tankoubon) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val m = service.members(serverUrl, LRRAuthManager.getActiveProfileId(), tank.id)
                _uiEvent.tryEmit(TankUiEvent.FillTank(m.tankId, m.name, m.members, m.memberIdsInOrder))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val context = ServiceRegistry.appModule.getContext()
                _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
            }
        }
    }

    fun loadTankoubons() {
        _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl()
                if (serverUrl == null) {
                    _isLoading.value = false // otherwise the scene spins forever
                    return@launch
                }

                val tanks = service.loadAll(serverUrl, LRRAuthManager.getActiveProfileId())
                _tanks.value = tanks
                _isLoading.value = false
                refreshCoverFallbacks(serverUrl, tanks)
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
                service.create(serverUrl, name)
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
     * Deletes a tankoubon from the server (never its archives), then reloads
     * the list.
     */
    fun delete(tankId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                service.delete(serverUrl, tankId)
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

            val tanks = service.loadAll(serverUrl, LRRAuthManager.getActiveProfileId())
            _tanks.value = tanks
            refreshCoverFallbacks(serverUrl, tanks)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to reload tankoubons after CRUD", e)
            val context = ServiceRegistry.appModule.getContext()
            _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
        }
    }

    private val service = TankListService(viewModelScope)

    /** Membership follow seam, forwarded to [TankListService.membershipSync]; replaceable for tests. */
    internal var membershipSync: TankMembershipSyncFactory.Runner
        get() = service.membershipSync
        set(value) {
            service.membershipSync = value
        }

    /**
     * Cover probing runs AFTER the list is published (the list used to wait
     * for one probe per tank on every load), and only for tanks without a
     * confirmed cover; a stand-in appears when its probe answers.
     */
    private fun refreshCoverFallbacks(serverUrl: String, tanks: List<LRRTankoubonApi.Tankoubon>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _coverFallbacks.value = service.probeCoverFallbacks(serverUrl, tanks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tank cover probe failed")
            }
        }
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
        const val HTTP_LOCKED = 423
        const val TAG = "TankoubonsViewModel"
    }
}
