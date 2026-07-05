package com.hippo.ehviewer.ui.scene

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.gallery.TankPageMath
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.TankoubonSupportGate
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * ViewModel for [TankoubonDetailScene]. Loads a single tankoubon's member
 * archives (with full metadata) from its SOURCE server, exposes the
 * global-page math inputs ([memberIds] / [pageOffsets]) the scene needs
 * for the read-from-start / continue-reading entries, and hosts the
 * management ops (rename / metadata / delete / member remove / reorder /
 * cover).
 *
 * The Scene observes [tankName] / [members] / [progress] / [isLoading]
 * for state and [uiEvent] for one-shot messages. View construction,
 * adapter, and navigation remain in the Scene.
 */
class TankoubonDetailViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // Identity (set once by init)
    // -------------------------------------------------------------------------

    /** LANraragi tank id (TANK_-prefixed); set once by [init]. */
    var tankId: String = ""
        private set

    /** Source profile that owns this tank; set once by [init]. */
    var profileId: Long = 0L
        private set

    /** Base URL resolved from [profileId] on the first [load]; null before. */
    @Volatile
    var baseUrl: String? = null
        private set

    private var initialized = false

    // -------------------------------------------------------------------------
    // Tank state
    // -------------------------------------------------------------------------

    private val _tankName = MutableStateFlow("")

    /** Tank display name; seeded from the nav arg, refreshed by [load]. */
    val tankName: StateFlow<String> = _tankName.asStateFlow()

    private val _members = MutableStateFlow<List<Archive>>(emptyList())

    /** Ordered member archives (server order), mapped with source context. */
    val members: StateFlow<List<Archive>> = _members.asStateFlow()

    private val _progress = MutableStateFlow(0)

    /** Global 1-indexed reading progress; 0/1 = nothing meaningful. */
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _isLoading = MutableStateFlow(false)

    /** Whether a load operation is in progress. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Ordered member arcids — single source of truth for membership. */
    @Volatile
    var memberIds: List<String> = emptyList()
        private set

    /** Prefix-sum global page offsets, see [TankPageMath.pageOffsets]. */
    @Volatile
    var pageOffsets: List<Int> = TankPageMath.pageOffsets(emptyList())
        private set

    /** Tank summary from the last load; null when the server sent none. */
    @Volatile
    var summary: String? = null
        private set

    /** Tank tags from the last load; null when the server sent none. */
    @Volatile
    var tags: String? = null
        private set

    /**
     * True once a load has succeeded, i.e. [summary]/[tags] hold server
     * truth. Gates the edit-metadata menu item in the scene: submitting
     * the dialog's empty defaults before a successful load would WIPE the
     * server-side summary/tags.
     */
    @Volatile
    var metaLoaded: Boolean = false
        private set

    /**
     * Cache-bust stamp bumped after a successful [setCover]. The image
     * pipeline caches by KEY, not URL, so the scene must derive BOTH a
     * busted cache key and a busted URL from this; 0 = cover never changed
     * in this session, use the plain key/url so normal loads hit the cache.
     */
    @Volatile
    var coverBust: Long = 0L
        private set

    // -------------------------------------------------------------------------
    // One-shot UI events
    // -------------------------------------------------------------------------

    private val _uiEvent = MutableSharedFlow<TankDetailUiEvent>(extraBufferCapacity = 8)

    /** One-shot events for the scene (errors / unsupported server). */
    val uiEvent: SharedFlow<TankDetailUiEvent> = _uiEvent.asSharedFlow()

    /**
     * Sealed interface for one-shot UI events emitted by this ViewModel.
     * The Scene observes [uiEvent] and dispatches via `when`.
     */
    sealed interface TankDetailUiEvent {
        data class ShowError(val message: String) : TankDetailUiEvent

        /** The source server lacks the 0.9.8 tankoubon detail routes. */
        data object ShowUnsupported : TankDetailUiEvent

        /** A management op succeeded; the scene toasts [messageResId]. */
        data class ShowSuccess(val messageResId: Int) : TankDetailUiEvent

        /** The tank was deleted server-side; the scene closes itself. */
        data object Deleted : TankDetailUiEvent
    }

    // -------------------------------------------------------------------------
    // API operations
    // -------------------------------------------------------------------------

    /**
     * Applies the nav args. Idempotent: only the FIRST call wins, so a view
     * recreation over a retained ViewModel keeps the loaded state intact.
     * Seeds [tankName] from the nav arg for an instant toolbar title.
     */
    fun init(tankId: String, name: String, profileId: Long) {
        if (initialized) return
        initialized = true
        this.tankId = tankId
        this.profileId = profileId
        _tankName.value = name
    }

    /**
     * Fetches the tank's full member metadata from its source server and
     * publishes name/progress/members plus the page-math inputs. A 404
     * flips [TankoubonSupportGate] and surfaces [TankDetailUiEvent.ShowUnsupported];
     * any other failure surfaces [TankDetailUiEvent.ShowError].
     */
    fun load() {
        _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = resolveSourceBaseUrl(
                    profileId,
                    ServiceRegistry.dataModule.profileLookupCache,
                )
                baseUrl = url
                val client = ServiceRegistry.networkModule.okHttpClient
                val full = LRRTankoubonApi.getTankoubonFull(client, url, tankId).result
                TankoubonSupportGate.markSupported(url)

                // Multi-profile red line: this tank may belong to a non-active
                // profile, so the mapper gets the EXPLICIT source context.
                val mapped = full.fullData.map {
                    it.toArchive(sourceProfileId = profileId, sourceBaseUrl = url)
                }
                memberIds = mapped.map { it.arcid }
                pageOffsets = TankPageMath.pageOffsets(mapped.map { it.pagecount })
                summary = full.summary
                tags = full.tags
                metaLoaded = true
                _tankName.value = full.name
                _progress.value = full.progress
                _members.value = mapped
                _isLoading.value = false
            } catch (e: Exception) {
                _isLoading.value = false
                val ctx = ServiceRegistry.appModule.getContext()
                val url = baseUrl
                if (url != null && TankoubonSupportGate.markFrom(url, e)) {
                    _uiEvent.tryEmit(TankDetailUiEvent.ShowUnsupported)
                } else {
                    Log.e(TAG, "Failed to load tankoubon", e)
                    _uiEvent.tryEmit(TankDetailUiEvent.ShowError(errorMessage(ctx, e)))
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Management ops
    // -------------------------------------------------------------------------

    /** Renames the tank, then reloads server truth. */
    fun rename(name: String) = mutateAndReload { client, url ->
        LRRTankoubonApi.renameTankoubon(client, url, tankId, name)
    }

    /** Updates summary/tags (never the member list), then reloads. */
    fun editMeta(summary: String, tags: String) = mutateAndReload { client, url ->
        // archives MUST stay null here — an empty list would WIPE the tank.
        LRRTankoubonApi.updateTankoubon(client, url, tankId, summary = summary, tags = tags)
    }

    /** Removes [arcid] from the tank, then reloads. */
    fun removeMember(arcid: String) = mutateAndReload { client, url ->
        LRRTankoubonApi.removeFromTankoubon(client, url, tankId, arcid)
    }

    /**
     * Deletes the tank server-side. Success surfaces
     * [TankDetailUiEvent.Deleted] (no reload / no success toast — the
     * Deleted event carries the outcome and closes the scene).
     */
    fun deleteTank() {
        viewModelScope.launch(Dispatchers.IO) {
            val url = requireBaseUrl() ?: return@launch
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                LRRTankoubonApi.deleteTankoubon(client, url, tankId)
                _uiEvent.tryEmit(TankDetailUiEvent.Deleted)
            } catch (e: Exception) {
                emitError(e)
            }
        }
    }

    /**
     * Sets the tank cover to the FIRST page of member [memberIndex]
     * (1-indexed global page at the API boundary). The server may answer
     * 200 with a queued Minion job before the thumbnail actually exists —
     * cosmetic, still treated as success. Bumps [coverBust] so the scene's
     * next cover bind bypasses the stale cached image.
     */
    fun setCover(memberIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val url = requireBaseUrl() ?: return@launch
            // offsets has size members+1; valid member indices are 0..size-2
            val offsets = pageOffsets
            if (memberIndex < 0 || memberIndex >= offsets.size - 1) return@launch
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                val page1 = TankPageMath.globalPage1(offsets, memberIndex, 0)
                LRRTankoubonApi.updateTankThumbnail(client, url, tankId, page1)
                coverBust = System.currentTimeMillis()
                _uiEvent.tryEmit(TankDetailUiEvent.ShowSuccess(R.string.tank_cover_updated))
            } catch (e: Exception) {
                emitError(e)
            }
        }
    }

    /**
     * Persists a new member order (the scene has already applied it
     * optimistically to its adapter). Authoritative VM state ([members] /
     * [memberIds] / [pageOffsets]) is updated BEFORE the network call:
     * [_members] is conflated on equality, so a failed call's [load]
     * rollback only re-renders if the VM state genuinely holds the new
     * order — and the continue-reading math needs [pageOffsets] recomputed
     * from the reordered pagecounts either way. On failure the server
     * truth is re-fetched (rollback) after a [R.string.tank_reorder_failed]
     * (or 423 locked) message.
     *
     * [newOrder] is reconciled against CURRENT membership first — a reload
     * can land mid-drag, so ids that no longer exist are dropped and ids
     * the drag snapshot didn't know about are appended (matching the
     * server's append-at-end semantics).
     */
    fun reorder(newOrder: List<String>) {
        val currentIds = _members.value.map { it.arcid }
        val currentSet = currentIds.toSet()
        val newOrderSet = newOrder.toSet()
        val finalOrder =
            newOrder.filter { it in currentSet } + currentIds.filter { it !in newOrderSet }
        if (finalOrder == memberIds) return
        val byId = _members.value.associateBy { it.arcid }
        val reordered = finalOrder.mapNotNull { byId[it] }
        val ids = reordered.map { it.arcid }
        memberIds = ids
        pageOffsets = TankPageMath.pageOffsets(reordered.map { it.pagecount })
        _members.value = reordered
        viewModelScope.launch(Dispatchers.IO) {
            val url = requireBaseUrl() ?: return@launch
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                LRRTankoubonApi.updateTankoubon(client, url, tankId, archives = ids)
            } catch (e: Exception) {
                val ctx = ServiceRegistry.appModule.getContext()
                val message = if (e is LRRHttpException && e.code == HTTP_LOCKED) {
                    errorMessage(ctx, e)
                } else {
                    ctx.getString(R.string.tank_reorder_failed)
                }
                _uiEvent.tryEmit(TankDetailUiEvent.ShowError(message))
                load() // rollback to server truth
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Shared scaffold for the simple mutate ops (rename / editMeta /
     * removeMember): IO launch, [baseUrl] guard, success toast + [load]
     * reload, [errorMessage]-mapped failure.
     */
    private fun mutateAndReload(op: suspend (client: OkHttpClient, url: String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val url = requireBaseUrl() ?: return@launch
            try {
                op(ServiceRegistry.networkModule.okHttpClient, url)
                _uiEvent.tryEmit(TankDetailUiEvent.ShowSuccess(R.string.tank_op_done))
                load()
            } catch (e: Exception) {
                emitError(e)
            }
        }
    }

    /**
     * [baseUrl] is only null while no [load] ever resolved the source
     * profile (resolution itself failed). Ops can't proceed then — surface
     * a generic error instead of silently dropping the action.
     */
    private fun requireBaseUrl(): String? {
        val url = baseUrl
        if (url == null) {
            val ctx = ServiceRegistry.appModule.getContext()
            _uiEvent.tryEmit(TankDetailUiEvent.ShowError(ctx.getString(R.string.error_unknown)))
        }
        return url
    }

    private fun emitError(e: Exception) {
        val ctx = ServiceRegistry.appModule.getContext()
        _uiEvent.tryEmit(TankDetailUiEvent.ShowError(errorMessage(ctx, e)))
    }

    /**
     * 423 (locked) gets a dedicated message — the server is busy regenerating
     * the tank; everything else goes through the shared [friendlyError] map.
     * Deliberately duplicated from TankoubonsViewModel: the two ViewModels
     * must stay independently evolvable.
     */
    private fun errorMessage(context: Context, e: Exception): String =
        if (e is LRRHttpException && e.code == HTTP_LOCKED) {
            context.getString(R.string.tank_locked)
        } else {
            friendlyError(context, e)
        }

    private companion object {
        const val HTTP_LOCKED = 423
        const val TAG = "TankoubonDetailViewModel"
    }
}
