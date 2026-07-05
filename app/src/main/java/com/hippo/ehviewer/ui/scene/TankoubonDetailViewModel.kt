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
import com.lanraragi.reader.client.api.runSuspend
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for [TankoubonDetailScene]. Loads a single tankoubon's member
 * archives (with full metadata) from its SOURCE server and exposes the
 * global-page math inputs ([memberIds] / [pageOffsets]) the scene needs
 * for the read-from-start / continue-reading entries.
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

        /** Reserved for Task 8 (membership/metadata edit success toast). */
        data class ShowSuccess(val messageResId: Int) : TankDetailUiEvent

        /** Reserved for Task 8 (tank deleted server-side → scene closes). */
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
                val full = runSuspend {
                    LRRTankoubonApi.getTankoubonFull(client, url, tankId)
                }.result
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
    // Internal helpers
    // -------------------------------------------------------------------------

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
