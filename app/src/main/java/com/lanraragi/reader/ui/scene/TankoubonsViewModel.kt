package com.lanraragi.reader.ui.scene

import com.lanraragi.reader.tankoubon.TankListCache
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.TankCoverCacheStamp
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.tankoubon.TankCategorySyncer
import com.lanraragi.reader.client.api.archiveThumbnailUrl
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.download.TankMembershipSync
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.gallery.TankSessionRouter
import com.lanraragi.reader.tankoubon.TankMemberOrderOps
import com.lanraragi.reader.ui.TankMembershipSyncFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /** Stand-in cover for a tank whose generated cover is missing server-side. */
    data class CoverFallback(val arcid: String, val thumbnailUrl: String)

    private val _coverFallbacks = MutableStateFlow<Map<String, CoverFallback>>(emptyMap())

    /**
     * tankId → first member's cover, for tanks the cover probe reported
     * WITHOUT a generated cover (probe 202; the probe itself queues
     * server-side generation). Published before [tanks] on each load so
     * cover binds already see it.
     */
    val coverFallbacks: StateFlow<Map<String, CoverFallback>> = _coverFallbacks.asStateFlow()

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
                val client = ServiceRegistry.networkModule.okHttpClient
                val full = LRRTankoubonApi.getTankoubonFull(client, serverUrl, tank.id).result
                val titles = full.fullData.associate { it.arcid to it.title }
                val sorted = TankMemberOrderOps.sortByTitle(full.archives) { titles[it].orEmpty() }
                if (sorted == full.archives) {
                    _uiEvent.tryEmit(TankUiEvent.ShowSuccess(R.string.tank_already_sorted))
                    return@launch
                }
                LRRTankoubonApi.updateTankoubon(client, serverUrl, tank.id, archives = sorted)
                _uiEvent.tryEmit(TankUiEvent.Sorted(tank.id, full.archives))
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
                val client = ServiceRegistry.networkModule.okHttpClient
                LRRTankoubonApi.updateTankoubon(client, serverUrl, tankId, archives = previousOrder)
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
                val client = ServiceRegistry.networkModule.okHttpClient
                val full = LRRTankoubonApi.getTankoubonFull(client, serverUrl, tank.id).result
                val profileId = LRRAuthManager.getActiveProfileId()
                val byId = full.fullData.associateBy { it.arcid }
                val members = full.archives.mapNotNull { id ->
                    byId[id]?.toArchive(sourceProfileId = profileId, sourceBaseUrl = serverUrl)
                }
                _uiEvent.tryEmit(TankUiEvent.FillTank(tank.id, full.name, members, full.archives))
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

                val tanks = fetchAllTanks(serverUrl)
                _tanks.value = tanks
                _isLoading.value = false
                syncMembership(serverUrl, tanks)
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
     * Deletes a tankoubon from the server (never its archives), then reloads
     * the list.
     */
    fun delete(tankId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                // Upstream leaves the deleted id dangling in static categories
                // (spec 2026-09-22 §6): clear it first, while the tank still exists.
                TankCategorySyncer.onDissolve(client, serverUrl, tankId, TankCategorySyncer.nameOf(client, serverUrl, tankId))
                LRRTankoubonApi.deleteTankoubon(client, serverUrl, tankId)
                // Dissolve any downloaded-tank grouping; member downloads
                // reappear standalone (files untouched).
                ServiceRegistry.dataModule.downloadManager.dissolveTankGroupAsync(tankId)

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

            val tanks = fetchAllTanks(serverUrl)
            _tanks.value = tanks
            syncMembership(serverUrl, tanks)
            refreshCoverFallbacks(serverUrl, tanks)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to reload tankoubons after CRUD", e)
            val context = ServiceRegistry.appModule.getContext()
            _uiEvent.tryEmit(TankUiEvent.ShowError(errorMessage(context, e)))
        }
    }

    /**
     * Membership follow seam (spec 2026-09-21 §1/§3): every successful list
     * fetch hands the server's member lists to [TankMembershipSync] so
     * downloaded group rows and member history rows follow the server.
     * Replaceable for tests; the default no-ops outside a live app.
     */
    internal var membershipSync: TankMembershipSyncFactory.Runner = TankMembershipSyncFactory.runnerSafely()

    private fun syncMembership(serverUrl: String, tanks: List<LRRTankoubonApi.Tankoubon>) {
        val profileId = LRRAuthManager.getActiveProfileId()
        val truth = tanks.map {
            TankMembershipSync.TankTruth(it.id, it.name, it.archives, it.progress, pagecount = 0)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                membershipSync.sync(profileId, serverUrl, truth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tank membership sync failed")
            }
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
        // Fresh server truth in hand — revalidate covers. Must precede the
        // callers' _tanks publication so cover binds already see the new stamp.
        TankCoverCacheStamp.bump()
        TankListCache.put(serverUrl, all)
        return ArrayList(all)
    }

    /** Tanks whose generated cover was confirmed: never probed again by this list. */
    private val confirmedCovers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Cover probing runs AFTER the list is published (the list used to wait
     * for one probe per tank on every load), and only for tanks without a
     * confirmed cover; a stand-in appears when its probe answers.
     */
    private fun refreshCoverFallbacks(serverUrl: String, tanks: List<LRRTankoubonApi.Tankoubon>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _coverFallbacks.value = probeCoverFallbacks(serverUrl, tanks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tank cover probe failed")
            }
        }
    }

    /**
     * Probes each member-having tank for a generated cover; the missing ones
     * ([LRRTankoubonApi.hasTankThumbnail] 202, which also queues server-side
     * generation) map to their FIRST member's cover as a visual stand-in —
     * matching what the tank's own cover would show once generated. A probe
     * failure degrades to "has cover" (plain URL → server placeholder), the
     * pre-probe behavior. Empty tanks are skipped: nothing to stand in AND
     * the server-side generation job would just fail.
     */
    private suspend fun probeCoverFallbacks(
        serverUrl: String,
        tanks: List<LRRTankoubonApi.Tankoubon>,
    ): Map<String, CoverFallback> = coroutineScope {
        val client = ServiceRegistry.networkModule.okHttpClient
        val gate = Semaphore(PROBE_PARALLELISM)
        tanks.filter { it.archives.isNotEmpty() && it.id !in confirmedCovers }
            .map { tank ->
                async {
                    val missing = try {
                        gate.withPermit { !LRRTankoubonApi.hasTankThumbnail(client, serverUrl, tank.id) }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ignored: Exception) {
                        false
                    }
                    if (!missing) {
                        confirmedCovers += tank.id
                        return@async null
                    }
                    val arcid = tank.archives.first()
                    val fallback = try {
                        CoverFallback(arcid, archiveThumbnailUrl(serverUrl, arcid))
                    } catch (ignored: Exception) {
                        null // malformed arcid — keep the plain tank URL
                    }
                    fallback?.let { tank.id to it }
                }
            }
            .awaitAll()
            .filterNotNull()
            .toMap()
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
        const val PROBE_PARALLELISM = 6
        const val HTTP_LOCKED = 423
        const val TAG = "TankoubonsViewModel"
    }
}
