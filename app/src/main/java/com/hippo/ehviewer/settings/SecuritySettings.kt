package com.hippo.ehviewer.settings

import android.util.Log
import com.hippo.ehviewer.Settings
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRSecureStorageUnavailableException

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
        if (LRRAuthManager.hasPattern()) return true
        // One-time migration: hash the legacy plaintext pattern
        val legacy = Settings.getString(KEY_SECURITY, "") ?: ""
        if (legacy.isNotEmpty()) {
            try {
                LRRAuthManager.setPattern(legacy)
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
     * Hash and store [pattern] in EncryptedSharedPreferences.
     * Pass null or empty string to clear the pattern.
     */
    @JvmStatic
    fun setPattern(pattern: String?) = LRRAuthManager.setPattern(pattern)

    /**
     * Verify [input] against the stored hash using a timing-safe comparison.
     * For non-KeyStore-bound patterns only. See [LRRAuthManager.verifyPatternWithCipher]
     * for KeyStore-bound pattern verification.
     */
    @JvmStatic
    fun verifyPattern(input: String?): Boolean = LRRAuthManager.verifyPattern(input)

    // --- Lockout ---

    /** @return true if the pattern is currently locked out due to too many failures. */
    @JvmStatic
    fun isLockedOut(): Boolean = LRRAuthManager.isLockedOut()

    /** @return remaining lockout duration in milliseconds, or 0. */
    @JvmStatic
    fun getLockoutRemainingMs(): Long = LRRAuthManager.getLockoutRemainingMs()

    /** @return true if the stored pattern is bound to Android KeyStore via AES-GCM. */
    @JvmStatic
    fun isPatternKeystoreBound(): Boolean = LRRAuthManager.isPatternKeystoreBound()

    // --- Fingerprint ---
    const val KEY_ENABLE_FINGERPRINT = "enable_fingerprint"

    @JvmStatic
    fun getEnableFingerprint(): Boolean = Settings.getBoolean(KEY_ENABLE_FINGERPRINT, false)

    @JvmStatic
    fun putEnableFingerprint(value: Boolean) = Settings.putBoolean(KEY_ENABLE_FINGERPRINT, value)
}
