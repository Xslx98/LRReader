package com.hippo.ehviewer.settings

import com.hippo.ehviewer.Settings

/**
 * Privacy and diagnostics settings: analytics consent, crash logging.
 */
object PrivacySettings {

    // --- Ask Analytics ---
    private const val KEY_ASK_ANALYTICS = "ask_analytics"
    private const val DEFAULT_ASK_ANALYTICS = true

    @JvmStatic
    fun getAskAnalytics(): Boolean = Settings.getBoolean(KEY_ASK_ANALYTICS, DEFAULT_ASK_ANALYTICS)

    @JvmStatic
    fun putAskAnalytics(value: Boolean) = Settings.putBoolean(KEY_ASK_ANALYTICS, value)

    // --- Enable Analytics ---
    @JvmField
    val KEY_ENABLE_ANALYTICS = "enable_analytics"
    private const val DEFAULT_ENABLE_ANALYTICS = false

    @JvmStatic
    fun getEnableAnalytics(): Boolean = Settings.getBoolean(KEY_ENABLE_ANALYTICS, DEFAULT_ENABLE_ANALYTICS)

    @JvmStatic
    fun putEnableAnalytics(value: Boolean) = Settings.putBoolean(KEY_ENABLE_ANALYTICS, value)

    // --- Save Crash Log ---
    private const val KEY_SAVE_CRASH_LOG = "save_crash_log"
    private const val DEFAULT_SAVE_CRASH_LOG = false

    @JvmStatic
    fun getSaveCrashLog(): Boolean = Settings.getBoolean(KEY_SAVE_CRASH_LOG, DEFAULT_SAVE_CRASH_LOG)

    // --- Delete Confirmation Countdown ---
    // Controls the 3-second cooldown on the destructive Delete button before it
    // becomes clickable. Default ON (safer); users can opt out for faster workflows.
    const val KEY_DELETE_CONFIRM_COUNTDOWN = "delete_confirm_countdown"
    private const val DEFAULT_DELETE_CONFIRM_COUNTDOWN = true

    @JvmStatic
    fun getDeleteConfirmCountdown(): Boolean =
        Settings.getBoolean(KEY_DELETE_CONFIRM_COUNTDOWN, DEFAULT_DELETE_CONFIRM_COUNTDOWN)
}
