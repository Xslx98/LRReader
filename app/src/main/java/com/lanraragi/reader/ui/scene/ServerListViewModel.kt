package com.lanraragi.reader.ui.scene

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.server.ServerCertificate
import com.lanraragi.reader.server.ServerProfileService
import com.lanraragi.reader.server.ServerIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for [ServerListScene]. Lists the server profiles and reports
 * the outcome of the profile changes [ServerProfileService] runs (add,
 * edit, switch, delete).
 *
 * The Scene observes [profiles] and [uiEvent] to update the UI.
 * View references, dialogs, navigation, and adapter setup remain
 * in the Scene.
 */
class ServerListViewModel(
    private val profileService: ServerProfileService = ServerProfileService.default,
) : ViewModel() {

    private val profileRepository = ServiceRegistry.dataModule.profileRepository

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private val _profiles = MutableStateFlow<List<ServerProfile>>(emptyList())

    /** Current list of server profiles, sorted with active profiles first. */
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    // -------------------------------------------------------------------------
    // One-shot events
    // -------------------------------------------------------------------------

    // A buffered channel, not a replay=0 SharedFlow: the ViewModel outlives
    // the scene's view, so an event that fires while the view is destroyed
    // (rotation, a covering scene) waits here for the next collector instead
    // of being dropped.
    private val _uiEvent = Channel<ServerListUiEvent>(Channel.BUFFERED)

    /** One-shot UI events (toasts, navigation, errors), each delivered once. */
    val uiEvent: Flow<ServerListUiEvent> = _uiEvent.receiveAsFlow()

    sealed interface ServerListUiEvent {
        data class ShowToast(val message: String) : ServerListUiEvent
        data class ShowToastRes(val resId: Int) : ServerListUiEvent
        data object SecureStorageError : ServerListUiEvent

        /** Profile activated — Scene should update ServerSession, clear caches, reload DM, navigate. */
        data class ProfileActivated(val profile: ServerProfile) : ServerListUiEvent

        /** A new profile was added and activated — Scene should navigate. */
        data class ProfileAdded(
            val profile: ServerProfile,
            val newId: Long,
            val info: ServerIdentity,
            val resolvedUrl: String,
            val usedHttpFallback: Boolean
        ) : ServerListUiEvent

        /** Edit save succeeded — Scene can dismiss dialog. */
        data class EditSaved(val position: Int, val updated: ServerProfile) : ServerListUiEvent

        /** Connection test failed during edit — Scene re-enables button. */
        data class EditConnectionFailed(val cause: Exception) : ServerListUiEvent

        /** Connection test failed during add — Scene re-enables button, auth is restored. */
        data class AddConnectionFailed(val cause: Exception) : ServerListUiEvent

        /**
         * The add (or edit, [forEdit]) connection test met a certificate
         * nothing trusts: Scene re-enables the dialog's button and asks the
         * user; trusting it repeats the test (audit C27 phase 2).
         */
        data class UntrustedCertificate(val certificate: ServerCertificate, val forEdit: Boolean) : ServerListUiEvent
    }

    // -------------------------------------------------------------------------
    // Profile loading
    // -------------------------------------------------------------------------

    fun loadProfiles() {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ArrayList(profileRepository.getAllProfiles()).also { list ->
                        list.sortWith(compareByDescending { it.isActive })
                    }
                }
                _profiles.value = result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load profiles", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Profile activation (switch)
    // -------------------------------------------------------------------------

    fun activateProfile(profile: ServerProfile) {
        viewModelScope.launch {
            when (profileService.activate(profile)) {
                // Scene handles toasts and navigation
                ServerProfileService.SwitchResult.SWITCHED ->
                    _uiEvent.send(ServerListUiEvent.ProfileActivated(profile))
                ServerProfileService.SwitchResult.SECURE_STORAGE_UNAVAILABLE ->
                    _uiEvent.send(ServerListUiEvent.SecureStorageError)
                ServerProfileService.SwitchResult.FAILED -> Unit
            }
        }
    }

    // -------------------------------------------------------------------------
    // Profile deletion
    // -------------------------------------------------------------------------

    fun deleteProfile(profile: ServerProfile) {
        val purge = profileService.delete(profile)
        if (purge == null) {
            _uiEvent.trySend(ServerListUiEvent.SecureStorageError)
            return
        }
        viewModelScope.launch {
            purge.await()
            // Reload to reflect deletion
            loadProfiles()
        }
    }

    // -------------------------------------------------------------------------
    // Profile edit — connection test + save
    // -------------------------------------------------------------------------

    /**
     * Tests connection for an edited profile and saves if successful.
     * Called from the edit dialog's save button.
     *
     * @param profile the original profile being edited
     * @param position adapter position of the profile
     * @param newName new server name
     * @param newUrl normalized URL input
     * @param newKey new API key (empty means null)
     */
    fun testAndSaveEditedProfile(
        profile: ServerProfile,
        position: Int,
        newName: String,
        newUrl: String,
        newKey: String,
        allowCleartext: Boolean
    ) {
        viewModelScope.launch {
            when (val r = profileService.connectAndSaveEdit(profile, newName, newUrl, newKey, allowCleartext)) {
                is ServerProfileService.Outcome.Done -> {
                    _uiEvent.send(ServerListUiEvent.EditSaved(position, r.value.updated))
                    if (r.value.usedHttpFallback) {
                        _uiEvent.send(ServerListUiEvent.ShowToastRes(R.string.lrr_https_fallback_warning))
                    }
                    // Reload profiles to reflect the change
                    loadProfiles()
                }
                is ServerProfileService.Outcome.Failed ->
                    _uiEvent.send(ServerListUiEvent.EditConnectionFailed(r.error))
                is ServerProfileService.Outcome.Untrusted ->
                    _uiEvent.send(ServerListUiEvent.UntrustedCertificate(r.certificate, forEdit = true))
                ServerProfileService.Outcome.SecureStorageUnavailable ->
                    _uiEvent.send(ServerListUiEvent.SecureStorageError)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Profile add — connection test + save + activate
    // -------------------------------------------------------------------------

    /**
     * Tests connection for a new profile, persists it, and activates it.
     *
     * @param name server name
     * @param normalizedUrl normalized URL input
     * @param apiKey API key (null for open servers)
     * @param allowCleartext whether the user has opted in to cleartext HTTP
     */
    fun testAndAddProfile(
        name: String,
        normalizedUrl: String,
        apiKey: String?,
        allowCleartext: Boolean
    ) {
        viewModelScope.launch {
            when (val r = profileService.connectAndAdd(name, normalizedUrl, apiKey, allowCleartext)) {
                is ServerProfileService.Outcome.Done -> {
                    val added = r.value
                    _uiEvent.send(
                        ServerListUiEvent.ProfileAdded(
                            profile = added.profile,
                            newId = added.profile.id,
                            info = added.info,
                            resolvedUrl = added.resolvedUrl,
                            usedHttpFallback = added.usedHttpFallback
                        )
                    )
                    loadProfiles()
                }
                is ServerProfileService.Outcome.Failed ->
                    _uiEvent.send(ServerListUiEvent.AddConnectionFailed(r.error))
                is ServerProfileService.Outcome.Untrusted ->
                    _uiEvent.send(ServerListUiEvent.UntrustedCertificate(r.certificate, forEdit = false))
                ServerProfileService.Outcome.SecureStorageUnavailable ->
                    _uiEvent.send(ServerListUiEvent.SecureStorageError)
            }
        }
    }

    companion object {
        private const val TAG = "ServerListVM"
    }
}
