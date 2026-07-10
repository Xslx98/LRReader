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
        newKey: String
    ) {
        // Snapshot the active credentials. connectWithFallback writes the URL
        // under test into the global active config, and we set the active key
        // below, both purely to drive the authenticated test request. If the
        // edited profile is NOT the active one (or the test fails), restore the
        // active config so editing profile B can't repoint active profile A at
        // B's server/key.
        val oldUrl: String? = LRRAuthManager.getServerUrl()
        val oldKey: String? = LRRAuthManager.getApiKey()
        try {
            LRRAuthManager.setApiKey(newKey.ifEmpty { null })
        } catch (e: LRRSecureStorageUnavailableException) {
            _uiEvent.tryEmit(ServerListUiEvent.SecureStorageError)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val testClient = LRRUrlHelper.buildTestClient(
                ServiceRegistry.networkModule.okHttpClient
            )
            try {
                LRRUrlHelper.connectWithFallback(
                    testClient,
                    newUrl,
                    newKey.ifEmpty { null },
                    object : LRRUrlHelper.ConnectCallback {
                        override fun onSuccess(
                            resolvedUrl: String,
                            info: LRRServerInfo,
                            usedHttpFallback: Boolean
                        ) {
                            // saveEditedProfile re-applies the active config only when
                            // the edited profile is active; for a non-active edit,
                            // restore what the test clobbered.
                            if (!profile.isActive) {
                                restoreActiveAuth(oldUrl, oldKey)
                            }
                            saveEditedProfile(profile, position, newName, resolvedUrl, newKey, usedHttpFallback)
                        }

                        override fun onFailure(error: Exception) {
                            restoreActiveAuth(oldUrl, oldKey)
                            _uiEvent.tryEmit(
                                ServerListUiEvent.EditConnectionFailed(error)
                            )
                        }
                    }
                )
            } catch (ce: CancellationException) {
                // ViewModel cleared mid-test: neither callback ran, so the
                // active config still holds the URL/key under test.
                restoreActiveAuth(oldUrl, oldKey)
                throw ce
            }
        }
    }

    /**
     * Restore the global active server URL + API key after a connection test
     * that mutated them as a side effect. Secure-storage failures are logged
     * and swallowed — losing the restore is non-fatal (the next profile switch
     * rewrites the active config).
     */
    private fun restoreActiveAuth(url: String?, key: String?) {
        try {
            if (url != null) LRRAuthManager.setServerUrl(url)
            LRRAuthManager.setApiKey(key)
        } catch (e: LRRSecureStorageUnavailableException) {
            Log.w(TAG, "Failed to restore active auth after connection test", e)
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
        // Preserve the user's existing cleartext choice; only force it on when this edit
        // actually went through an HTTP fallback (which implies the user confirmed it).
        val updated = ServerProfile(
            id = profile.id,
            name = newName,
            url = resolvedUrl,
            isActive = profile.isActive,
            allowCleartext = if (usedHttpFallback) true else profile.allowCleartext
        )
        val isActive = profile.isActive
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    profileRepository.update(updated)
                    try {
                        LRRAuthManager.setApiKeyForProfile(profile.id, newKey.ifEmpty { null })
                        if (isActive) {
                            LRRAuthManager.setServerUrl(updated.url)
                            LRRAuthManager.setApiKey(newKey.ifEmpty { null })
                            LRRAuthManager.setServerName(newName)
                            LRRAuthManager.setAllowCleartext(updated.allowCleartext)
                            LRRAuthManager.bumpServerConfigVersion()
                        }
                        LRRAuthManager.markReauthIfProfilesUnprotected(
                            profileRepository.getAllProfiles().map { it.id }
                        )
                    } catch (e: LRRSecureStorageUnavailableException) {
                        _uiEvent.emit(ServerListUiEvent.SecureStorageError)
                        return@withContext
                    }
                }
                _uiEvent.emit(ServerListUiEvent.EditSaved(position, updated))
                if (usedHttpFallback) {
                    _uiEvent.emit(ServerListUiEvent.ShowToastRes(
                        com.hippo.ehviewer.R.string.lrr_https_fallback_warning
                    ))
                }
                // Reload profiles to reflect the change
                loadProfiles()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save edited profile", e)
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

        // Save old auth so we can restore on failure
        val oldUrl: String? = LRRAuthManager.getServerUrl()
        val oldKey: String? = LRRAuthManager.getApiKey()
        try {
            LRRAuthManager.setApiKey(finalKey)
        } catch (e: LRRSecureStorageUnavailableException) {
            _uiEvent.tryEmit(ServerListUiEvent.SecureStorageError)
            return
        }

        val baseClient = ServiceRegistry.networkModule.okHttpClient
        val testClient = LRRUrlHelper.buildTestClient(baseClient)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                LRRUrlHelper.connectWithFallback(
                    testClient,
                    normalizedUrl,
                    finalKey,
                    object : LRRUrlHelper.ConnectCallback {
                        override fun onSuccess(
                            resolvedUrl: String,
                            info: LRRServerInfo,
                            usedHttpFallback: Boolean
                        ) {
                            performAddProfile(
                                name, resolvedUrl, finalKey, allowCleartext,
                                info, usedHttpFallback
                            )
                        }

                        override fun onFailure(error: Exception) {
                            // Restore old auth on failure
                            restoreActiveAuth(oldUrl, oldKey)
                            _uiEvent.tryEmit(
                                ServerListUiEvent.AddConnectionFailed(error)
                            )
                        }
                    }
                )
            } catch (ce: CancellationException) {
                // ViewModel cleared mid-test: neither callback ran, so the
                // active config still holds the URL/key under test.
                restoreActiveAuth(oldUrl, oldKey)
                throw ce
            }
        }
    }

    private fun performAddProfile(
        name: String,
        resolvedUrl: String,
        finalKey: String?,
        allowCleartext: Boolean,
        info: LRRServerInfo,
        usedHttpFallback: Boolean
    ) {
        val resolvedIsHttp = resolvedUrl.lowercase().startsWith("http://")
        val savedAllowCleartext = if (resolvedIsHttp) allowCleartext else true

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
