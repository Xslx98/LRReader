package com.hippo.ehviewer.settings

import com.hippo.ehviewer.Settings

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

    // --- Password Lock ---
    const val KEY_SECURITY = "security"
    const val DEFAULT_SECURITY = ""

    @JvmStatic
    fun getSecurity(): String = Settings.getString(KEY_SECURITY, DEFAULT_SECURITY) ?: DEFAULT_SECURITY

    @JvmStatic
    fun putSecurity(value: String?) = Settings.putString(KEY_SECURITY, value)

    // --- Fingerprint ---
    const val KEY_ENABLE_FINGERPRINT = "enable_fingerprint"

    @JvmStatic
    fun getEnableFingerprint(): Boolean = Settings.getBoolean(KEY_ENABLE_FINGERPRINT, false)

    @JvmStatic
    fun putEnableFingerprint(value: Boolean) = Settings.putBoolean(KEY_ENABLE_FINGERPRINT, value)
}
