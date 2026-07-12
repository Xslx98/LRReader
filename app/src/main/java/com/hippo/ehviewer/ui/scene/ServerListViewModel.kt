package com.hippo.ehviewer.ui.scene

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.ServerProfile
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRSecureStorageUnavailableException
import com.lanraragi.reader.client.api.LRRServerApi
import com.lanraragi.reader.client.api.LRRUrlHelper
import com.lanraragi.reader.client.api.data.LRRServerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for [ServerListScene]. Manages server profile CRUD,
 * connection verification, and profile activation (cache clearing +
 * DownloadManager reload).
 *
 * The Scene observes [profiles] and [uiEvent] to update the UI.
 * View references, dialogs, navigation, and adapter setup remain
 * in the Scene.
 */
class ServerListViewModel : ViewModel() {

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

    private val _uiEvent = MutableSharedFlow<ServerListUiEvent>(extraBufferCapacity = 4)

    /** One-shot UI events (toasts, navigation, errors). */
    val uiEvent: SharedFlow<ServerListUiEvent> = _uiEvent.asSharedFlow()

    sealed interface ServerListUiEvent {
        data class ShowToast(val message: String) : ServerListUiEvent
        data class ShowToastRes(val resId: Int) : ServerListUiEvent
        data object SecureStorageError : ServerListUiEvent

        /** Profile activated — Scene should update LRRAuthManager, clear caches, reload DM, navigate. */
        data class ProfileActivated(val profile: ServerProfile) : ServerListUiEvent

        /** A new profile was added and activated — Scene should navigate. */
        data class ProfileAdded(
            val profile: ServerProfile,
            val newId: Long,
            val info: LRRServerInfo,
            val resolvedUrl: String,
            val usedHttpFallback: Boolean
        ) : ServerListUiEvent

        /** Edit save succeeded — Scene can dismiss dialog. */
        data class EditSaved(val position: Int, val updated: ServerProfile) : ServerListUiEvent

        /** Connection test failed during edit — Scene re-enables button. */
        data class EditConnectionFailed(val cause: Exception) : ServerListUiEvent

        /** Connection test failed during add — Scene re-enables button, auth is restored. */
        data class AddConnectionFailed(val cause: Exception) : ServerListUiEvent
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
            try {
                withContext(Dispatchers.IO) {
                    // Single atomic statement: flips IS_ACTIVE for the chosen profile and
                    // clears it for all others without a window where none is active. Other
                    // columns (name/url/allowCleartext) are untouched and thus preserved.
                    profileRepository.activateExclusive(profile.id)
                }
                // Scene handles LRRAuthManager update, cache clearing, DM reload, and navigation
                _uiEvent.emit(ServerListUiEvent.ProfileActivated(profile))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to activate profile", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Profile deletion
    // -------------------------------------------------------------------------

    fun deleteProfile(profile: ServerProfile) {
        try {
            LRRAuthManager.clearApiKeyForProfile(profile.id)
        } catch (e: LRRSecureStorageUnavailableException) {
            _uiEvent.tryEmit(ServerListUiEvent.SecureStorageError)
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    profileRepository.delete(profile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete profile", e)
            }
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
        viewModelScope.launch(Dispatchers.IO) {
            val testClient = LRRUrlHelper.buildTestClient(
                ServiceRegistry.networkModule.okHttpClient
            )
            // NET-7: the probe is self-contained (explicit Bearer header on a
            // stripped test client) — no global auth state is touched, so
            // there is nothing to snapshot, restore, or guard against cancel.
            // Gate on the edit dialog's current consent, not the stale stored
            // flag, so a just-granted plain-HTTP opt-in is honoured and a
            // revoked one is enforced.
            when (
                val r = LRRUrlHelper.connectWithFallback(
                    testClient, newUrl, newKey.ifEmpty { null }, allowCleartext
                )
            ) {
                is LRRUrlHelper.ConnectResult.Success ->
                    saveEditedProfile(
                        profile, position, newName, r.resolvedUrl, newKey, r.usedHttpFallback
                    )
                is LRRUrlHelper.ConnectResult.Failure ->
                    _uiEvent.tryEmit(ServerListUiEvent.EditConnectionFailed(r.error))
            }
        }
    }

    private fun saveEditedProfile(
        profile: ServerProfile,
        position: Int,
        newName: String,
        resolvedUrl: String,
        newKey: String,
        usedHttpFallback: Boolean
    ) {
        // The persisted cleartext flag tracks the resolved scheme: an HTTP
        // resolution must be allowed cleartext or LRRCleartextRejectionInterceptor
        // refuses the profile's traffic; an HTTPS resolution needs no grant. The
        // gate already enforced the user's consent for a WAN downgrade.
        val updated = ServerProfile(
            id = profile.id,
            name = newName,
            url = resolvedUrl,
            isActive = profile.isActive,
            allowCleartext = resolvedUrl.lowercase().startsWith("http://")
        )
        val isActive = profile.isActive
        viewModelScope.launch {
            try {
                val committed = withContext(Dispatchers.IO) {
                    try {
                        // Write the fragile secure-storage state first: if the
                        // keystore is unavailable the first call throws before Room
                        // is touched, so the edit aborts cleanly instead of leaving
                        // Room updated while live auth still points at the old URL.
                        LRRAuthManager.setApiKeyForProfile(profile.id, newKey.ifEmpty { null })
                        if (isActive) {
                            LRRAuthManager.setServerUrl(updated.url)
                            LRRAuthManager.setApiKey(newKey.ifEmpty { null })
                            LRRAuthManager.setServerName(newName)
                            LRRAuthManager.setAllowCleartext(updated.allowCleartext)
                            LRRAuthManager.bumpServerConfigVersion()
                        }
                    } catch (e: LRRSecureStorageUnavailableException) {
                        _uiEvent.emit(ServerListUiEvent.SecureStorageError)
                        return@withContext false
                    }
                    profileRepository.update(updated)
                    LRRAuthManager.markReauthIfProfilesUnprotected(
                        profileRepository.getAllProfiles().map { it.id }
                    )
                    true
                }
                if (!committed) return@launch
                _uiEvent.emit(ServerListUiEvent.EditSaved(position, updated))
                if (usedHttpFallback) {
                    _uiEvent.emit(ServerListUiEvent.ShowToastRes(
                        com.hippo.ehviewer.R.string.lrr_https_fallback_warning
                    ))
                }
                // Reload profiles to reflect the change
                loadProfiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The keystore writes already committed but persistence failed
                // (e.g. a Room write error). Surface it so the edit dialog
                // re-enables instead of soft-locking with the Save button stuck
                // disabled and no feedback.
                Log.e(TAG, "Failed to save edited profile", e)
                _uiEvent.emit(ServerListUiEvent.EditConnectionFailed(e))
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
        val finalKey: String? = apiKey?.ifEmpty { null }

        val baseClient = ServiceRegistry.networkModule.okHttpClient
        val testClient = LRRUrlHelper.buildTestClient(baseClient)

        viewModelScope.launch(Dispatchers.IO) {
            // NET-7: the probe is self-contained (explicit Bearer header on a
            // stripped test client) — no global auth state is touched, so
            // there is nothing to snapshot, restore, or guard against cancel.
            when (
                val r = LRRUrlHelper.connectWithFallback(
                    testClient, normalizedUrl, finalKey, allowCleartext
                )
            ) {
                is LRRUrlHelper.ConnectResult.Success ->
                    performAddProfile(name, r.resolvedUrl, finalKey, r.info, r.usedHttpFallback)
                is LRRUrlHelper.ConnectResult.Failure ->
                    _uiEvent.tryEmit(ServerListUiEvent.AddConnectionFailed(r.error))
            }
        }
    }

    private fun performAddProfile(
        name: String,
        resolvedUrl: String,
        finalKey: String?,
        info: LRRServerInfo,
        usedHttpFallback: Boolean
    ) {
        // The persisted cleartext flag tracks the resolved scheme, not the
        // gate opt-in: a profile that resolved to HTTP must be allowed
        // cleartext or LRRCleartextRejectionInterceptor refuses all its
        // traffic; an HTTPS profile needs no cleartext grant. The gate
        // (connectWithFallback's allowCleartext) already refused any
        // unconsented WAN-cleartext resolution before reaching here.
        val savedAllowCleartext = resolvedUrl.lowercase().startsWith("http://")

        viewModelScope.launch {
            try {
                // Set auth immediately
                try {
                    LRRAuthManager.setServerUrl(resolvedUrl)
                    LRRAuthManager.setApiKey(finalKey)
                    LRRAuthManager.setServerName(name)
                    LRRAuthManager.setAllowCleartext(savedAllowCleartext)
                    LRRAuthManager.bumpServerConfigVersion()
                } catch (e: LRRSecureStorageUnavailableException) {
                    _uiEvent.emit(ServerListUiEvent.SecureStorageError)
                    return@launch
                }

                val newId = withContext(Dispatchers.IO) {
                    profileRepository.deactivateAll()
                    val newProfile = ServerProfile(
                        id = 0,
                        name = name,
                        url = resolvedUrl,
                        isActive = true,
                        allowCleartext = savedAllowCleartext
                    )
                    val id = profileRepository.insert(newProfile)
                    try {
                        LRRAuthManager.setApiKeyForProfile(id, finalKey)
                        LRRAuthManager.setActiveProfileId(id)
                    } catch (e: LRRSecureStorageUnavailableException) {
                        _uiEvent.emit(ServerListUiEvent.SecureStorageError)
                        return@withContext -1L
                    }
                    id
                }
                if (newId < 0) return@launch

                val profile = ServerProfile(
                    id = newId,
                    name = name,
                    url = resolvedUrl,
                    isActive = true,
                    allowCleartext = savedAllowCleartext
                )
                _uiEvent.emit(
                    ServerListUiEvent.ProfileAdded(
                        profile = profile,
                        newId = newId,
                        info = info,
                        resolvedUrl = resolvedUrl,
                        usedHttpFallback = usedHttpFallback
                    )
                )
                loadProfiles()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add profile", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connection verification
    // -------------------------------------------------------------------------

    /**
     * Test connection to the active server after profile edits.
     * Shows a toast on failure via [uiEvent].
     */
    fun verifyActiveProfile(url: String) {
        viewModelScope.launch {
            try {
                val testClient = withContext(Dispatchers.IO) {
                    LRRUrlHelper.buildTestClient(ServiceRegistry.networkModule.okHttpClient)
                }
                withContext(Dispatchers.IO) {
                    // Always targets the active profile; the test client strips
                    // LRRAuthInterceptor (NET-7), so attach the active key here.
                    LRRServerApi.getServerInfo(testClient, url, LRRAuthManager.getApiKey())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Verification failed for $url", e)
                _uiEvent.emit(ServerListUiEvent.ShowToast(e.message ?: "Unknown error"))
            }
        }
    }

    companion object {
        private const val TAG = "ServerListVM"
    }
}
