package com.hippo.ehviewer.ui.scene

import android.util.Log
import androidx.lifecycle.ViewModel
import com.hippo.ehviewer.settings.SecuritySettings
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.Cipher

/**
 * ViewModel for [SecurityScene]. Manages pattern verification state,
 * retry counting, and lockout tracking.
 *
 * The Scene retains ownership of BiometricPrompt (requires Fragment),
 * View references, and sensor/shake detection (hardware lifecycle).
 * The ViewModel owns verification logic and retry/lockout state.
 */
class SecurityViewModel : ViewModel() {

    companion object {
        private const val TAG = "SecurityViewModel"
        const val MAX_RETRY_TIMES = 5
    }

    // -------------------------------------------------------------------------
    // Retry state
    // -------------------------------------------------------------------------

    private val _retryTimes = MutableStateFlow(MAX_RETRY_TIMES)

    /** Current remaining retry attempts before the Scene finishes. */
    val retryTimes: StateFlow<Int> = _retryTimes.asStateFlow()

    // -------------------------------------------------------------------------
    // Lockout state
    // -------------------------------------------------------------------------

    data class LockoutState(
        val isLockedOut: Boolean = false,
        val remainingSeconds: Int = 0
    )

    private val _lockoutState = MutableStateFlow(LockoutState())

    /** Current lockout state. Scene observes this to update the lockout UI. */
    val lockoutState: StateFlow<LockoutState> = _lockoutState.asStateFlow()

    // -------------------------------------------------------------------------
    // One-shot UI events
    // -------------------------------------------------------------------------

    sealed interface SecurityUiEvent {
        /** Pattern verified successfully — Scene should navigate forward. */
        data object PatternVerified : SecurityUiEvent

        /** Pattern verification failed — Scene should show error display. */
        data object PatternFailed : SecurityUiEvent

        /** All retries exhausted — Scene should finish. */
        data object RetriesExhausted : SecurityUiEvent

        /**
         * KeyStore-bound pattern needs biometric authentication.
         * Scene should show BiometricPrompt with the given [pattern]
         * and call back [onBiometricVerificationResult] or
         * [onBiometricCipherUnavailable].
         */
        data class NeedBiometricVerification(val pattern: String) : SecurityUiEvent
    }

    private val _uiEvent = MutableSharedFlow<SecurityUiEvent>(extraBufferCapacity = 1)

    /** One-shot events for the Scene to react to. */
    val uiEvent: SharedFlow<SecurityUiEvent> = _uiEvent.asSharedFlow()

    // -------------------------------------------------------------------------
    // State restoration
    // -------------------------------------------------------------------------

    /**
     * Restores retry count from saved instance state.
     * Called by the Scene in [SecurityScene.onCreate].
     */
    fun restoreRetryTimes(times: Int) {
        _retryTimes.value = times
    }

    // -------------------------------------------------------------------------
    // Pattern verification
    // -------------------------------------------------------------------------

    /**
     * Called when a pattern is detected. Determines the verification path
     * and emits the appropriate event.
     */
    fun onPatternDetected(patternString: String) {
        if (SecuritySettings.isLockedOut()) {
            refreshLockout()
            return
        }

        if (SecuritySettings.isPatternKeystoreBound()) {
            // Scene needs to show BiometricPrompt — emit event with the pattern
            _uiEvent.tryEmit(SecurityUiEvent.NeedBiometricVerification(patternString))
        } else {
            // PBKDF2-only: verify directly
            if (SecuritySettings.verifyPattern(patternString)) {
                _uiEvent.tryEmit(SecurityUiEvent.PatternVerified)
            } else {
                onVerificationFailed()
            }
        }
    }

    /**
     * Called after biometric-authenticated cipher verification completes.
     * The Scene calls this with the result from BiometricPrompt.
     */
    fun onBiometricVerificationResult(patternString: String, cipher: Cipher?) {
        if (cipher != null && LRRAuthManager.verifyPatternWithCipher(patternString, cipher)) {
            _uiEvent.tryEmit(SecurityUiEvent.PatternVerified)
        } else {
            onVerificationFailed()
        }
    }

    /**
     * Called when biometric prompt fails to get cipher (KeyStore invalidated).
     * Falls back to PBKDF2 verification.
     */
    fun onBiometricCipherUnavailable(patternString: String) {
        if (SecuritySettings.verifyPattern(patternString)) {
            _uiEvent.tryEmit(SecurityUiEvent.PatternVerified)
        } else {
            onVerificationFailed()
        }
    }

    /**
     * Gets the decrypt cipher for KeyStore-bound pattern verification.
     * Returns null if the cipher is unavailable (KeyStore invalidated).
     */
    fun getDecryptCipher(): Cipher? {
        return try {
            LRRAuthManager.getDecryptCipher()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get decrypt cipher", e)
            null
        }
    }

    /**
     * Refreshes lockout state from SecuritySettings.
     * Called by the Scene's lockout update runnable and on resume.
     */
    fun refreshLockout() {
        val remainingMs = SecuritySettings.getLockoutRemainingMs()
        _lockoutState.value = LockoutState(
            isLockedOut = remainingMs > 0,
            remainingSeconds = ((remainingMs + 999) / 1000).toInt()
        )
    }

    private fun onVerificationFailed() {
        _retryTimes.value = _retryTimes.value - 1
        refreshLockout()
        if (_retryTimes.value <= 0) {
            _uiEvent.tryEmit(SecurityUiEvent.RetriesExhausted)
        } else {
            _uiEvent.tryEmit(SecurityUiEvent.PatternFailed)
        }
    }
}
