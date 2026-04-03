package com.hippo.ehviewer.settings

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.lrr.LRRAuthManager

/**
 * Security-related settings extracted from Settings.java.
 * Covers secure mode (FLAG_SECURE), password lock, and fingerprint authentication.
 */
object SecuritySettings {

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
            LRRAuthManager.setPattern(legacy)
            Settings.putString(KEY_SECURITY, "")
            return true
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
     */
    @JvmStatic
    fun verifyPattern(input: String?): Boolean = LRRAuthManager.verifyPattern(input)

    // --- Fingerprint ---
    const val KEY_ENABLE_FINGERPRINT = "enable_fingerprint"

    @JvmStatic
    fun getEnableFingerprint(): Boolean = Settings.getBoolean(KEY_ENABLE_FINGERPRINT, false)

    @JvmStatic
    fun putEnableFingerprint(value: Boolean) = Settings.putBoolean(KEY_ENABLE_FINGERPRINT, value)
}
