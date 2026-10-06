package com.lanraragi.reader.ui.scene

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.lanraragi.reader.R
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

        /**
         * The fingerprint prompt reported success, but its cipher did not
         * open the pattern key's blob: no unlock, the pattern is required.
         */
        data object FingerprintUnverified : SecurityUiEvent

        /** The fingerprint prompt's cipher opened the pattern key's blob: unlock. */
        data object FingerprintVerified : SecurityUiEvent
    }

    /** Whether the lock screen may offer fingerprint unlock now (audit 2026-10-06e SEC-01). */
    sealed interface FingerprintStart {
        /** Authenticate with this cipher as the prompt's CryptoObject. */
        data class Ready(val cipher: Cipher) : FingerprintStart

        /** Fingerprint unlock is not switched on. */
        data object Off : FingerprintStart

        /**
         * Fingerprint unlock was on but cannot be proven any more (key
         * invalidated by a new enrolment, or never bound): it has just been
         * switched off. Tell the user [reason]; the pattern unlocks.
         */
        data class TurnedOff(@StringRes val reason: Int) : FingerprintStart

        /** The keystore failed for another reason: pattern only this time, the setting stays. */
        data object Unavailable : FingerprintStart
    }

    /** What the lock screen can offer while the secure store is unreadable (audit SEC-04). */
    enum class StorageState {
        /** The pattern can be checked. */
        AVAILABLE,

        /** Init has not finished yet: transient, offer only a retry. */
        STARTING,

        /**
         * Init finished and the store could not be opened: retry, plus the
         * reset from the second failed launch ([unavailablePrompt]).
         */
        UNAVAILABLE,
    }

    fun storageState(): StorageState = when (LRRAuthManager.secureStorageState()) {
        LRRAuthManager.SecureStorageState.AVAILABLE -> StorageState.AVAILABLE
        LRRAuthManager.SecureStorageState.STARTING -> StorageState.STARTING
        LRRAuthManager.SecureStorageState.UNAVAILABLE -> StorageState.UNAVAILABLE
    }

    /** The UNAVAILABLE prompt's wording and whether it offers the reset (audit 06d SEC-01). */
    internal fun unavailablePrompt(): StorageUnavailablePrompt.Text = StorageUnavailablePrompt.of(
        lockKnownSet = SecuritySettings.isLockKnownSet(),
        offerReset = LRRAuthManager.canOfferLockScreenReset(),
    )

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
            // Already off when the lock screen switched it off on resume
            // (startFingerprintUnlock) and told the user: do not tell twice.
            val wasOn = SecuritySettings.getEnableFingerprint()
            SecuritySettings.putEnableFingerprint(false)
            _uiEvent.tryEmit(
                if (wasOn) SecurityUiEvent.VerifiedAfterFingerprintChange else SecurityUiEvent.PatternVerified
            )
        } else {
            onVerificationFailed()
        }
    }

    /**
     * Decides whether the lock screen offers fingerprint unlock, and with
     * which CryptoObject cipher (audit 2026-10-06e SEC-01). A prompt without
     * a CryptoObject accepted any enrolled finger, including one added after
     * the lock was set, so fingerprint unlock now needs the keystore-bound
     * pattern key. When that key was invalidated (new enrolment) or never
     * bound (pattern saved without the biometric confirmation, e.g. the
     * prompt was cancelled), fingerprint unlock is switched off and the
     * pattern is required.
     *
     * It is not re-bound after a pattern unlock: that would need a second
     * biometric prompt on every such unlock. Setting the pattern again
     * (Privacy settings, fingerprint box ticked) creates a fresh key and
     * turns fingerprint unlock back on.
     */
    fun startFingerprintUnlock(): FingerprintStart {
        if (!SecuritySettings.getEnableFingerprint()) return FingerprintStart.Off
        if (!LRRAuthManager.isPatternKeystoreBound()) {
            return turnFingerprintOff(R.string.security_fingerprint_not_bound)
        }
        return try {
            FingerprintStart.Ready(LRRAuthManager.getDecryptCipher())
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.e(TAG, "Fingerprint key invalidated", e)
            turnFingerprintOff(R.string.security_fingerprint_changed)
        } catch (e: LRRAuthManager.PatternKeyMissingException) {
            Log.e(TAG, "Fingerprint key missing", e)
            turnFingerprintOff(R.string.security_fingerprint_not_bound)
        } catch (e: Exception) {
            Log.e(TAG, "Fingerprint cipher unavailable", e)
            FingerprintStart.Unavailable
        }
    }

    private fun turnFingerprintOff(@StringRes reason: Int): FingerprintStart {
        SecuritySettings.putEnableFingerprint(false)
        return FingerprintStart.TurnedOff(reason)
    }

    /**
     * The fingerprint prompt reported success. Unlocks only if its
     * CryptoObject cipher really decrypts the pattern key's blob.
     */
    fun onFingerprintAuthenticated(cipher: Cipher?) {
        if (LRRAuthManager.verifyFingerprintCipher(cipher)) {
            _uiEvent.tryEmit(SecurityUiEvent.FingerprintVerified)
        } else {
            _uiEvent.tryEmit(SecurityUiEvent.FingerprintUnverified)
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
