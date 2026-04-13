package com.hippo.ehviewer.ui.scene

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.ServerProfile
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRSecureStorageUnavailableException
import com.lanraragi.reader.client.api.LRRServerApi
import com.lanraragi.reader.client.api.LRRUrlHelper
import com.lanraragi.reader.client.api.data.LRRServerInfo
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
        data class EditConnectionFailed(val message: String) : ServerListUiEvent

        /** Connection test failed during add — Scene re-enables button, auth is restored. */
        data class AddConnectionFailed(val message: String) : ServerListUiEvent
    }

    // -------------------------------------------------------------------------
    // Profile loading
    // -------------------------------------------------------------------------

    fun loadProfiles() {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ArrayList(EhDB.getAllServerProfilesAsync()).also { list ->
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
                    EhDB.deactivateAllProfilesAsync()
                    EhDB.updateServerProfileAsync(
                        ServerProfile(
                            id = profile.id,
                            name = profile.name,
                            url = profile.url,
                            isActive = true
                        )
                    )
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
                    EhDB.deleteServerProfileAsync(profile)
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
            LRRUrlHelper.connectWithFallback(
                testClient,
                newUrl,
                object : LRRUrlHelper.ConnectCallback {
                    override fun onSuccess(
                        resolvedUrl: String,
                        info: LRRServerInfo,
                        usedHttpFallback: Boolean
                    ) {
                        saveEditedProfile(profile, position, newName, resolvedUrl, newKey, usedHttpFallback)
                    }

                    override fun onFailure(error: Exception) {
                        _uiEvent.tryEmit(
                            ServerListUiEvent.EditConnectionFailed(error.message ?: "Unknown error")
                        )
                    }
                }
            )
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
        val isHttpUrl = resolvedUrl.lowercase().startsWith("http://")
        val updated = ServerProfile(
            id = profile.id,
            name = newName,
            url = resolvedUrl,
            isActive = profile.isActive,
            allowCleartext = if (isHttpUrl) true else true
        )
        val isActive = profile.isActive
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    EhDB.updateServerProfileAsync(updated)
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
                            EhDB.getAllServerProfilesAsync().map { it.id }
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
            LRRUrlHelper.connectWithFallback(
                testClient,
                normalizedUrl,
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
                        try {
                            oldUrl?.let { LRRAuthManager.setServerUrl(it) }
                            LRRAuthManager.setApiKey(oldKey)
                        } catch (_: LRRSecureStorageUnavailableException) {
                            // Secure storage down — proceed with original failure
                        }
                        _uiEvent.tryEmit(
                            ServerListUiEvent.AddConnectionFailed(error.message ?: "Unknown error")
                        )
                    }
                }
            )
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
                    EhDB.deactivateAllProfilesAsync()
                    val newProfile = ServerProfile(
                        id = 0,
                        name = name,
                        url = resolvedUrl,
                        isActive = true,
                        allowCleartext = savedAllowCleartext
                    )
                    val id = EhDB.insertServerProfileAsync(newProfile)
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
                    LRRServerApi.getServerInfo(testClient, url)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Verification failed for $url", e)
                _uiEvent.emit(ServerListUiEvent.ShowToast(e.message ?: "Unknown error"))
            }
        }
    }

    companion object {
        private const val TAG = "ServerListVM"
    }
}
