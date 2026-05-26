package com.hippo.ehviewer.settings

import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings
import java.util.Date

/**
 * Update-related settings: auto-check, beta channel, skip version, last-check time.
 *
 * Note: KEY_CLOSE_AUTO_UPDATES (legacy inverted-semantics key from the EhViewer scaffold)
 * was removed in v1.14.0 along with the upper-level in-app update feature launch. No
 * migration is performed — the legacy key was never wired into production code paths.
 */
object UpdateSettings {

    // --- Beta Update Channel ---
    private const val KEY_BETA_UPDATE_CHANNEL = "beta_update_channel"
    private val DEFAULT_BETA_UPDATE_CHANNEL = EhApplication.BETA

    @JvmStatic
    fun getBetaUpdateChannel(): Boolean =
        Settings.getBoolean(KEY_BETA_UPDATE_CHANNEL, DEFAULT_BETA_UPDATE_CHANNEL)

    @JvmStatic
    fun putBetaUpdateChannel(value: Boolean) =
        Settings.putBoolean(KEY_BETA_UPDATE_CHANNEL, value)

    // --- Skip Update Version ---
    private const val KEY_SKIP_UPDATE_VERSION = "skip_update_version"
    private const val DEFAULT_SKIP_UPDATE_VERSION = 0

    @JvmStatic
    fun getSkipUpdateVersion(): Int =
        Settings.getInt(KEY_SKIP_UPDATE_VERSION, DEFAULT_SKIP_UPDATE_VERSION)

    @JvmStatic
    fun putSkipUpdateVersion(value: Int) =
        Settings.putInt(KEY_SKIP_UPDATE_VERSION, value)

    // --- Auto-check for updates (replaces the legacy KEY_CLOSE_AUTO_UPDATES) ---
    const val KEY_AUTO_CHECK_UPDATES = "auto_check_for_updates"

    private const val DEFAULT_AUTO_CHECK_UPDATES = true

    @JvmStatic
    fun getAutoCheckUpdates(): Boolean =
        Settings.getBoolean(KEY_AUTO_CHECK_UPDATES, DEFAULT_AUTO_CHECK_UPDATES)

    @JvmStatic
    fun setAutoCheckUpdates(value: Boolean) =
        Settings.putBoolean(KEY_AUTO_CHECK_UPDATES, value)

    // --- Last Update Time (1-day throttle for auto-check) ---
    @JvmField
    val KEY_LAST_UPDATE_TIME = "last_update_time"

    private const val DEFAULT_LAST_UPDATE_TIME = 0L

    @JvmStatic
    fun getIsUpdateTime(): Boolean {
        val lastUpdateTime = Settings.getLong(KEY_LAST_UPDATE_TIME, DEFAULT_LAST_UPDATE_TIME)
        val now = Date().time
        val msNum = now - lastUpdateTime
        val dayNum = msNum / (1000 * 60 * 60 * 24)
        return dayNum >= 1
    }

    @JvmStatic
    fun putUpdateTime(updateTime: Long) {
        Settings.putLong(KEY_LAST_UPDATE_TIME, updateTime)
    }
}
