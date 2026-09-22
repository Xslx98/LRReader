package com.lanraragi.reader.ui.scene

import android.util.Log
import androidx.lifecycle.ViewModel
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.Cipher

/**
 * ViewModel for [SecurityScene]. Manages pattern verification state
 * and lockout tracking.
 *
 * The Scene retains ownership of BiometricPrompt (requires Fragment),
 * and View references.
 * The ViewModel owns verification logic and lockout state.
 */
class SecurityViewModel : ViewModel() {

    companion object {
        private const val TAG = "SecurityViewModel"
    }

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

        /**
         * KeyStore-bound pattern needs biometric authentication.
         * Scene should show BiometricPrompt with the given [pattern]
         * and call back [onBiometricVerificationResult] or
         * [onBiometricCipherUnavailable].
         */
        data class NeedBiometricVerification(val pattern: String) : SecurityUiEvent

        /**
         * The pattern was verified, but only after the fingerprint key had
         * been invalidated (new fingerprint enrolled): fingerprint unlock
         * was switched off. Scene should tell the user, then navigate on.
         */
        data object VerifiedAfterFingerprintChange : SecurityUiEvent

        /**
         * The fingerprint key is gone and the pattern was saved without a
         * plain hash, so it can never be verified again. Scene should offer
         * resetting the app lock.
         */
        data object PatternUnverifiable : SecurityUiEvent
    }

    private val _uiEvent = MutableSharedFlow<SecurityUiEvent>(extraBufferCapacity = 1)

    /** One-shot events for the Scene to react to. */
    val uiEvent: SharedFlow<SecurityUiEvent> = _uiEvent.asSharedFlow()

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
     * Called when the keystore cipher cannot be obtained, typically
     * KeyPermanentlyInvalidatedException after a new fingerprint was
     * enrolled. Falls back to the plain PBKDF2 hash; on success the dead
     * keystore binding and fingerprint unlock are switched off.
     */
    fun onBiometricCipherUnavailable(patternString: String) {
        if (!LRRAuthManager.canVerifyWithoutKeystore()) {
            _uiEvent.tryEmit(SecurityUiEvent.PatternUnverifiable)
            return
        }
        if (SecuritySettings.verifyPattern(patternString)) {
            LRRAuthManager.unbindPatternFromKeystore()
            SecuritySettings.putEnableFingerprint(false)
            _uiEvent.tryEmit(SecurityUiEvent.VerifiedAfterFingerprintChange)
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

    /**
     * A wrong pattern never dismisses the lock screen: brute force is
     * throttled by the persistent, escalating lockout instead (the old
     * "5 tries then finish()" popped the prompt and revealed the scene
     * underneath).
     */
    private fun onVerificationFailed() {
        refreshLockout()
        _uiEvent.tryEmit(SecurityUiEvent.PatternFailed)
    }
}
