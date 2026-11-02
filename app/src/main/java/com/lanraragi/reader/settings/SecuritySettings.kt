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

    /**
     * @return true if an app-lock pattern has been set.
     *
     * Performs a one-time migration: if a plaintext pattern was stored under the
     * legacy Settings key it is hashed into EncryptedSharedPreferences and the
     * plaintext is erased.
     */
    @JvmStatic
    fun hasPattern(): Boolean {
        if (AppLockCredentials.hasPattern()) return true
        // One-time migration: hash the legacy plaintext pattern
        val legacy = Settings.getString(KEY_SECURITY, "") ?: ""
        if (legacy.isNotEmpty()) {
            try {
                AppLockCredentials.setPattern(legacy)
                Settings.putString(KEY_SECURITY, "")
                return true
            } catch (e: LRRSecureStorageUnavailableException) {
                // KeyStore unavailable — leave legacy plaintext in place so migration
                // can retry next launch, and report "no pattern" for this session.
                Log.w(TAG, "Could not migrate legacy pattern to secure storage", e)
                return false
            }
        }
        return false
    }

    /**
     * Whether the app lock is on — the value every lock gate must use.
     *
     * Reads the plain-prefs mirror, so it needs no keystore work (safe on the
     * launch path) and FAILS CLOSED: when the secure store cannot be opened,
     * [hasPattern] reports false, but the mirror still says the app is
     * locked. Before this version has written the mirror (first launch after
     * upgrading from <= v1.26) it decides without the keystore and reports
     * "locked" while that is not possible (audit 2026-10-06c SEC-01, see
     * [AppLockCredentials.isLockSetOrUnknown]).
     */
    @JvmStatic
    fun isLockEnabled(): Boolean {
        if (!Settings.getString(KEY_SECURITY, "").isNullOrEmpty()) {
            // Pre-hash plaintext pattern: locked. Migrate it while the mirror
            // is unwritten, as before.
            if (AppLockCredentials.lockEnabledHint() == null) hasPattern()
            return true
        }
        return AppLockCredentials.isLockSetOrUnknown()
    }

    /**
     * Whether a lock is known to be set, as opposed to only "cannot be ruled
     * out" ([isLockEnabled] is true for both). For the lock screen's wording:
     * a user who never set a lock must not be told to reset one.
     */
    @JvmStatic
    fun isLockKnownSet(): Boolean =
        !Settings.getString(KEY_SECURITY, "").isNullOrEmpty() ||
            AppLockCredentials.lockStateWithoutKeystore() == true

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
