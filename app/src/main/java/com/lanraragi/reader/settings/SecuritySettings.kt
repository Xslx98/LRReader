package com.lanraragi.reader.settings

import android.util.Log
import com.lanraragi.reader.Settings
import com.lanraragi.reader.security.AppLockCredentials

/**
 * Security-related settings extracted from Settings.java.
 * Covers secure mode (FLAG_SECURE), password lock, and fingerprint authentication.
 */
object SecuritySettings {

    private const val TAG = "SecuritySettings"

    // --- Secure Mode (FLAG_SECURE) ---
    const val KEY_SEC_SECURITY = "enable_secure"
    private const val DEFAULT_SEC_SECURITY = false

    @JvmStatic
    fun getEnabledSecurity(): Boolean = Settings.getBoolean(KEY_SEC_SECURITY, DEFAULT_SEC_SECURITY)

    @JvmStatic
    fun putEnabledSecurity(value: Boolean) = Settings.putBoolean(KEY_SEC_SECURITY, value)

    // --- Password / Pattern Lock ---

    /** @return true if an app-lock pattern is stored (hashed, or the pre-hash plaintext not yet migrated). */
    @JvmStatic
    fun hasPattern(): Boolean = AppLockCredentials.hasPattern() || AppLockCredentials.hasLegacyPlaintextPattern()

    /**
     * Boot step, once the secure store has opened: hash a plaintext pattern
     * stored by the oldest versions under the legacy Settings key into the
     * secure store, then erase the plaintext. With the store unavailable the
     * plaintext stays (the app stays locked, see [isLockEnabled]) and the
     * next boot retries.
     */
    @JvmStatic
    fun migrateLegacyPattern() {
        val legacy = AppLockCredentials.legacyPlaintextPattern()
        if (legacy.isEmpty()) return
        try {
            AppLockCredentials.setPattern(legacy)
            Settings.putString(AppLockCredentials.KEY_LEGACY_PLAINTEXT_PATTERN, "")
        } catch (e: Exception) {
            // Store unavailable, or no PBKDF2 here: the plaintext stays (still
            // locked) and the next boot, or an unlock with it, retries. Never
            // let it abort the rest of the boot.
            Log.e(TAG, "Could not migrate legacy pattern to secure storage", e)
        }
    }

    /**
     * Whether the app lock is on — the value every lock gate must use.
     *
     * [AppLockCredentials.lockState]: reads the plain-prefs mirror, so it
     * needs no keystore work (safe on the launch path) and FAILS CLOSED: when
     * the secure store cannot be opened, [hasPattern] reports false, but the
     * mirror still says the app is locked (audit 2026-10-06c SEC-01). A
     * plaintext pattern not yet migrated ([migrateLegacyPattern]) is locked too.
     */
    @JvmStatic
    fun isLockEnabled(): Boolean = AppLockCredentials.lockState().isLocked

    /**
     * Whether a lock is known to be set, as opposed to only "cannot be ruled
     * out" ([isLockEnabled] is true for both). For the lock screen's wording:
     * a user who never set a lock must not be told to reset one.
     */
    @JvmStatic
    fun isLockKnownSet(): Boolean =
        AppLockCredentials.hasLegacyPlaintextPattern() || AppLockCredentials.lockStateWithoutKeystore() == true

    /**
     * Hash and store [pattern] in EncryptedSharedPreferences.
     * Pass null or empty string to clear the pattern.
     */
    @JvmStatic
    fun setPattern(pattern: String?) = AppLockCredentials.setPattern(pattern)

    /**
     * Verify [input] against the stored hash using a timing-safe comparison.
     * For non-KeyStore-bound patterns only. See [AppLockCredentials.verifyPatternWithCipher]
     * for KeyStore-bound pattern verification.
     */
    @JvmStatic
    fun verifyPattern(input: String?): Boolean {
        val legacy = AppLockCredentials.legacyPlaintextPattern()
        if (legacy.isEmpty() || AppLockCredentials.hasPattern()) return AppLockCredentials.verifyPattern(input)
        // Only the plaintext exists (the boot step has not run yet, or the store
        // was down at boot): check it, with the same lockout, and hash it on success.
        if (AppLockCredentials.isLockedOut()) return false
        val ok = input != null &&
            java.security.MessageDigest.isEqual(input.toByteArray(Charsets.UTF_8), legacy.toByteArray(Charsets.UTF_8))
        if (ok) {
            AppLockCredentials.resetFailures()
            migrateLegacyPattern()
        } else {
            AppLockCredentials.recordFailure()
        }
        return ok
    }

    // --- Lockout ---

    /** @return true if the pattern is currently locked out due to too many failures. */
    @JvmStatic
    fun isLockedOut(): Boolean = AppLockCredentials.isLockedOut()

    /** @return remaining lockout duration in milliseconds, or 0. */
    @JvmStatic
    fun getLockoutRemainingMs(): Long = AppLockCredentials.getLockoutRemainingMs()

    /** @return true if the stored pattern is bound to Android KeyStore via AES-GCM. */
    @JvmStatic
    fun isPatternKeystoreBound(): Boolean = AppLockCredentials.isPatternKeystoreBound()

    // --- Fingerprint ---
    const val KEY_ENABLE_FINGERPRINT = "enable_fingerprint"

    @JvmStatic
    fun getEnableFingerprint(): Boolean = Settings.getBoolean(KEY_ENABLE_FINGERPRINT, false)

    @JvmStatic
    fun putEnableFingerprint(value: Boolean) = Settings.putBoolean(KEY_ENABLE_FINGERPRINT, value)
}
