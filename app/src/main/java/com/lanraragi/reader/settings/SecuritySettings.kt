package com.lanraragi.reader.settings

import android.util.Log
import com.lanraragi.reader.Settings
import com.lanraragi.reader.security.AppLockCredentials
import com.lanraragi.reader.server.LRRSecureStorageUnavailableException

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
    // Legacy key — used only for one-time migration to hashed storage.
    private const val KEY_SECURITY = "security"

    /** A pre-hash plaintext pattern is still stored under the legacy Settings key. */
    private fun hasLegacyPlaintextPattern(): Boolean = !Settings.getString(KEY_SECURITY, "").isNullOrEmpty()

    /** @return true if an app-lock pattern is stored (hashed, or the pre-hash plaintext not yet migrated). */
    @JvmStatic
    fun hasPattern(): Boolean = AppLockCredentials.hasPattern() || hasLegacyPlaintextPattern()

    /**
     * Boot step, once the secure store has opened: hash a plaintext pattern
     * stored by the oldest versions under the legacy Settings key into the
     * secure store, then erase the plaintext. With the store unavailable the
     * plaintext stays (the app stays locked, see [isLockEnabled]) and the
     * next boot retries.
     */
    @JvmStatic
    fun migrateLegacyPattern() {
        val legacy = Settings.getString(KEY_SECURITY, "").orEmpty()
        if (legacy.isEmpty()) return
        try {
            AppLockCredentials.setPattern(legacy)
            Settings.putString(KEY_SECURITY, "")
        } catch (e: LRRSecureStorageUnavailableException) {
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
    fun isLockEnabled(): Boolean = hasLegacyPlaintextPattern() || AppLockCredentials.lockState().isLocked

    /**
     * Whether a lock is known to be set, as opposed to only "cannot be ruled
     * out" ([isLockEnabled] is true for both). For the lock screen's wording:
     * a user who never set a lock must not be told to reset one.
     */
    @JvmStatic
    fun isLockKnownSet(): Boolean =
        hasLegacyPlaintextPattern() || AppLockCredentials.lockStateWithoutKeystore() == true

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
    fun verifyPattern(input: String?): Boolean = AppLockCredentials.verifyPattern(input)

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
