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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for [ServerConfigScene]. Manages connection state, protocol
 * auto-detection, form validation, and server profile persistence.
 *
 * The Scene observes [StateFlow] / [SharedFlow] properties and updates
 * the UI accordingly. View references, dialogs, and navigation remain
 * in the Scene.
 */
class ServerConfigViewModel : ViewModel() {

    private val profileRepository = ServiceRegistry.dataModule.profileRepository

    // -------------------------------------------------------------------------
    // Connection state
    // -------------------------------------------------------------------------

    private val _connecting = MutableStateFlow(false)

    /** Whether a connection attempt is currently in progress. */
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    // -------------------------------------------------------------------------
    // One-shot events
    // -------------------------------------------------------------------------

    /**
     * Emitted when a connection attempt succeeds. Contains all data the
     * Scene needs to update the UI and optionally navigate.
     */
    data class ConnectSuccess(
        val resolvedUrl: String,
        val serverInfo: LRRServerInfo,
        val navigateOnSuccess: Boolean
    )

    private val _connectSuccess = MutableSharedFlow<ConnectSuccess>(extraBufferCapacity = 1)

    /** Emitted once per successful connection. */
    val connectSuccess: SharedFlow<ConnectSuccess> = _connectSuccess.asSharedFlow()

    private val _connectFailure = MutableSharedFlow<Exception>(extraBufferCapacity = 1)

    /** Emitted once per failed connection with the causal exception. */
    val connectFailure: SharedFlow<Exception> = _connectFailure.asSharedFlow()

    private val _secureStorageError = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted when EncryptedSharedPreferences are unavailable. */
    val secureStorageError: SharedFlow<Unit> = _secureStorageError.asSharedFlow()

    // -------------------------------------------------------------------------
    // Connection logic
    // -------------------------------------------------------------------------

    /**
     * Initiates a connection attempt with protocol auto-detection.
     *
     * 1. If user typed explicit scheme -> use as-is
     * 2. If no scheme -> try https:// first, on failure try http://
     *
     * @param rawInput normalized server URL
     * @param apiKey API key (may be null/empty for open servers)
     * @param navigateOnSuccess if true, emit navigate signal on success (Connect button);
     *                          if false, just report server info (Test button)
     */
    fun attemptConnection(rawInput: String, apiKey: String?, navigateOnSuccess: Boolean) {
        if (_connecting.value) return

        _connecting.value = true

        // Save API key
        try {
            LRRAuthManager.setApiKey(if (!apiKey.isNullOrEmpty()) apiKey else null)
        } catch (e: LRRSecureStorageUnavailableException) {
            _connecting.value = false
            _secureStorageError.tryEmit(Unit)
            return
        }

        val client = ServiceRegistry.networkModule.okHttpClient
        val testClient = LRRUrlHelper.buildTestClient(client)

        try {
            if (LRRUrlHelper.hasExplicitScheme(rawInput)) {
                // User specified protocol explicitly -- use as-is
                LRRAuthManager.setServerUrl(rawInput)
                tryConnect(testClient, rawInput, null, navigateOnSuccess)
            } else {
                // No explicit scheme: try HTTPS first, then fall back to HTTP
                val httpsUrl = "https://$rawInput"
                val httpUrl = "http://$rawInput"
                LRRAuthManager.setServerUrl(httpsUrl) // temp set for interceptor
                tryConnect(testClient, httpsUrl, httpUrl, navigateOnSuccess)
            }
        } catch (e: LRRSecureStorageUnavailableException) {
            _connecting.value = false
            _secureStorageError.tryEmit(Unit)
        }
    }

    /**
     * Try connecting to primaryUrl. On failure, if fallbackUrl is non-null, try that too.
     */
    private fun tryConnect(
        client: okhttp3.OkHttpClient,
        primaryUrl: String,
        fallbackUrl: String?,
        navigateOnSuccess: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // --- Attempt 1: primary URL ---
            try {
                Log.d(TAG, "Trying primary URL: $primaryUrl")
                val info = LRRServerApi.getServerInfo(client, primaryUrl)
                // Success on primary
                onConnectSuccess(primaryUrl, info, navigateOnSuccess)
                return@launch
            } catch (e1: Exception) {
                Log.d(TAG, "Primary URL failed: ${e1.message}")

                if (fallbackUrl == null) {
                    // No fallback -- report failure
                    onConnectFailure(e1)
                    return@launch
                }
            }

            // --- Attempt 2: fallback URL ---
            try {
                Log.d(TAG, "Trying fallback URL: $fallbackUrl")
                // Update interceptor URL for fallback attempt
                LRRAuthManager.setServerUrl(fallbackUrl)
                val info = LRRServerApi.getServerInfo(client, fallbackUrl)
                // Success on fallback
                onConnectSuccess(fallbackUrl, info, navigateOnSuccess)
            } catch (e: LRRSecureStorageUnavailableException) {
                Log.e(TAG, "Secure storage unavailable during fallback", e)
                onConnectFailure(e)
            } catch (e2: Exception) {
                Log.d(TAG, "Fallback URL also failed: ${e2.message}")
                onConnectFailure(e2)
            }
        }
    }

    /**
     * Called on IO thread when connection succeeds. Persists/updates the
     * [ServerProfile] then emits the success event.
     */
    private suspend fun onConnectSuccess(
        resolvedUrl: String,
        info: LRRServerInfo,
        navigateOnSuccess: Boolean
    ) {
        try {
            LRRAuthManager.setServerUrl(resolvedUrl)
            LRRAuthManager.setServerName(info.name)

            // Create or update ServerProfile
            val existing = profileRepository.findByUrl(resolvedUrl)
            profileRepository.deactivateAll()
            if (existing != null) {
                // API key is stored in EncryptedSharedPreferences, not in Room
                val updated = ServerProfile(
                    existing.id,
                    info.name ?: existing.name,
                    resolvedUrl,
                    true
                )
                profileRepository.update(updated)
                LRRAuthManager.setApiKeyForProfile(existing.id, LRRAuthManager.getApiKey())
                LRRAuthManager.setActiveProfileId(existing.id)
            } else {
                val profileName = info.name ?: "LANraragi"
                // API key is stored in EncryptedSharedPreferences, not in Room
                val newProfile = ServerProfile(0, profileName, resolvedUrl, true)
                val newId = profileRepository.insert(newProfile)
                LRRAuthManager.setApiKeyForProfile(newId, LRRAuthManager.getApiKey())
                LRRAuthManager.setActiveProfileId(newId)
            }
        } catch (e: LRRSecureStorageUnavailableException) {
            Log.e(TAG, "Secure storage unavailable on connect success", e)
            onConnectFailure(e)
            return
        }

        _connecting.value = false
        _connectSuccess.tryEmit(ConnectSuccess(resolvedUrl, info, navigateOnSuccess))
    }

    /**
     * Called when all connection attempts fail. Emits the failure event.
     */
    private fun onConnectFailure(e: Exception) {
        _connecting.value = false
        if (e is LRRSecureStorageUnavailableException) {
            _secureStorageError.tryEmit(Unit)
        } else {
            _connectFailure.tryEmit(e)
        }
    }

    /**
     * Checks whether the resolved URL uses HTTP on a non-LAN address.
     */
    fun isInsecureWanConnection(resolvedUrl: String): Boolean {
        return resolvedUrl.lowercase().startsWith("http://") &&
            !LRRUrlHelper.isLanAddress(resolvedUrl)
    }

    companion object {
        private const val TAG = "ServerConfigVM"
    }
}
