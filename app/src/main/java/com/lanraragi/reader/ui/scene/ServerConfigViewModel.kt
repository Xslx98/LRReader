package com.lanraragi.reader.ui.scene

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.server.LRRSecureStorageUnavailableException
import com.lanraragi.reader.server.ServerCertificate
import com.lanraragi.reader.server.ServerProfileService
import com.lanraragi.reader.server.ServerIdentity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for [ServerConfigScene]. Manages connection state and reports
 * the outcome of [ServerProfileService.connectAndActivate], which does the
 * protocol auto-detection and the profile persistence.
 *
 * The Scene observes [StateFlow] / [SharedFlow] properties and updates
 * the UI accordingly. View references, dialogs, and navigation remain
 * in the Scene.
 */
class ServerConfigViewModel(
    private val profiles: ServerProfileService = ServerProfileService.default,
) : ViewModel() {

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
        val serverInfo: ServerIdentity,
        val navigateOnSuccess: Boolean
    )

    // Buffered channels, not replay=0 SharedFlows: the ViewModel outlives the
    // scene's view, so a result that lands while the view is destroyed
    // (rotation, a drawer scene on top) waits for the next collector instead
    // of being dropped — the service has already switched the server by then.
    private val _connectSuccess = Channel<ConnectSuccess>(Channel.BUFFERED)

    /** Emitted once per successful connection. */
    val connectSuccess: Flow<ConnectSuccess> = _connectSuccess.receiveAsFlow()

    private val _connectFailure = Channel<Exception>(Channel.BUFFERED)

    /** Emitted once per failed connection with the causal exception. */
    val connectFailure: Flow<Exception> = _connectFailure.receiveAsFlow()

    private val _secureStorageError = Channel<Unit>(Channel.BUFFERED)

    /** Emitted when EncryptedSharedPreferences are unavailable. */
    val secureStorageError: Flow<Unit> = _secureStorageError.receiveAsFlow()

    /** A certificate the user must decide on, with the attempt to repeat once trusted. */
    data class UntrustedCertificate(
        val certificate: ServerCertificate,
        val navigateOnSuccess: Boolean
    )

    private val _untrustedCertificate = Channel<UntrustedCertificate>(Channel.BUFFERED)

    /** Emitted when the server's certificate is not trusted (audit C27 phase 2). */
    val untrustedCertificate: Flow<UntrustedCertificate> = _untrustedCertificate.receiveAsFlow()

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
    fun attemptConnection(
        rawInput: String,
        apiKey: String?,
        navigateOnSuccess: Boolean,
        allowCleartext: Boolean = false
    ) {
        if (_connecting.value) return

        _connecting.value = true

        viewModelScope.launch {
            // The service finishes the test and the commit even if this
            // screen closes first; only the report below needs the screen.
            when (val r = profiles.connectAndActivate(rawInput, apiKey, allowCleartext)) {
                is ServerProfileService.Outcome.Done -> {
                    _connecting.value = false
                    _connectSuccess.trySend(ConnectSuccess(r.value.resolvedUrl, r.value.info, navigateOnSuccess))
                }
                is ServerProfileService.Outcome.Failed -> onConnectFailure(r.error)
                ServerProfileService.Outcome.SecureStorageUnavailable -> {
                    _connecting.value = false
                    _secureStorageError.trySend(Unit)
                }
                is ServerProfileService.Outcome.Untrusted -> {
                    _connecting.value = false
                    _untrustedCertificate.trySend(UntrustedCertificate(r.certificate, navigateOnSuccess))
                }
            }
        }
    }

    /**
     * Called when all connection attempts fail. Nothing to roll back — a test
     * attempt never writes global auth state (NET-7); just emit the failure.
     */
    private fun onConnectFailure(e: Exception) {
        _connecting.value = false
        if (e is LRRSecureStorageUnavailableException) {
            _secureStorageError.trySend(Unit)
        } else {
            _connectFailure.trySend(e)
        }
    }
}
