package com.hippo.ehviewer.ui.scene

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.ServerProfile
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRSecureStorageUnavailableException
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

        // NET-7: the candidate key rides the test request itself (explicit
        // Bearer header on a stripped test client). No global auth state is
        // written until onConnectSuccess — the single commit point.
        val candidateKey = apiKey?.ifEmpty { null }
        val testClient = LRRUrlHelper.buildTestClient(ServiceRegistry.networkModule.okHttpClient)

        viewModelScope.launch(Dispatchers.IO) {
            // Delegate protocol detection, HTTPS->HTTP fallback, and the
            // cleartext LAN gate to the shared helper (the same one
            // ServerListViewModel uses) so onboarding can never probe a WAN
            // host over cleartext with the API key attached.
            LRRUrlHelper.connectWithFallback(
                testClient,
                rawInput,
                candidateKey,
                object : LRRUrlHelper.ConnectCallback {
                    override fun onSuccess(
                        resolvedUrl: String,
                        info: LRRServerInfo,
                        usedHttpFallback: Boolean
                    ) {
                        onConnectSuccess(resolvedUrl, info, candidateKey, navigateOnSuccess)
                    }

                    override fun onFailure(error: Exception) {
                        onConnectFailure(error)
                    }
                }
            )
        }
    }

    /**
     * Called on IO thread when connection succeeds. Persists/updates the
     * [ServerProfile] then emits the success event.
     */
    private fun onConnectSuccess(
        resolvedUrl: String,
        info: LRRServerInfo,
        apiKey: String?,
        navigateOnSuccess: Boolean
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // NET-7 commit point: global auth state is written only after
                    // the candidate server actually responded.
                    LRRAuthManager.setServerUrl(resolvedUrl)
                    LRRAuthManager.setApiKey(apiKey)
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
                        LRRAuthManager.setApiKeyForProfile(existing.id, apiKey)
                        LRRAuthManager.setActiveProfileId(existing.id)
                    } else {
                        val profileName = info.name ?: "LANraragi"
                        // API key is stored in EncryptedSharedPreferences, not in Room
                        val newProfile = ServerProfile(0, profileName, resolvedUrl, true)
                        val newId = profileRepository.insert(newProfile)
                        LRRAuthManager.setApiKeyForProfile(newId, apiKey)
                        LRRAuthManager.setActiveProfileId(newId)
                    }
                }
            } catch (e: LRRSecureStorageUnavailableException) {
                Log.e(TAG, "Secure storage unavailable on connect success", e)
                onConnectFailure(e)
                return@launch
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to persist profile on connect success", e)
                onConnectFailure(e)
                return@launch
            }

            _connecting.value = false
            _connectSuccess.tryEmit(ConnectSuccess(resolvedUrl, info, navigateOnSuccess))
        }
    }

    /**
     * Called when all connection attempts fail. Nothing to roll back — a test
     * attempt never writes global auth state (NET-7); just emit the failure.
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
        return LRRUrlHelper.isInsecureWanUrl(resolvedUrl)
    }

    companion object {
        private const val TAG = "ServerConfigVM"
    }
}
