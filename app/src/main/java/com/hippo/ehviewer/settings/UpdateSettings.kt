package com.hippo.ehviewer.settings

import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings
import java.util.Date

/**
 * Update-related settings: auto-update, beta channel, skip version, update time tracking.
 */
object UpdateSettings {

    // --- Beta Update Channel ---
    private const val KEY_BETA_UPDATE_CHANNEL = "beta_update_channel"
    private val DEFAULT_BETA_UPDATE_CHANNEL = EhApplication.BETA

    @JvmStatic
    fun getBetaUpdateChannel(): Boolean = Settings.getBoolean(KEY_BETA_UPDATE_CHANNEL, DEFAULT_BETA_UPDATE_CHANNEL)

    @JvmStatic
    fun putBetaUpdateChannel(value: Boolean) = Settings.putBoolean(KEY_BETA_UPDATE_CHANNEL, value)

    // --- Skip Update Version ---
    private const val KEY_SKIP_UPDATE_VERSION = "skip_update_version"
    private const val DEFAULT_SKIP_UPDATE_VERSION = 0

    @JvmStatic
    fun getSkipUpdateVersion(): Int = Settings.getInt(KEY_SKIP_UPDATE_VERSION, DEFAULT_SKIP_UPDATE_VERSION)

    @JvmStatic
    fun putSkipUpdateVersion(value: Int) = Settings.putInt(KEY_SKIP_UPDATE_VERSION, value)

    // --- Close Auto Updates ---
    @JvmField
    val KEY_CLOSE_AUTO_UPDATES = "close_auto_updates"

    private const val DEFAULT_CLOSE_AUTO_UPDATES = false

    @JvmStatic
    fun getCloseAutoUpdate(): Boolean = Settings.getBoolean(KEY_CLOSE_AUTO_UPDATES, DEFAULT_CLOSE_AUTO_UPDATES)

    @JvmStatic
    fun setKeyCloseAutoUpdates(value: Boolean) = Settings.putBoolean(KEY_CLOSE_AUTO_UPDATES, value)

    // --- Last Update Time ---
    @JvmField
    val KEY_LAST_UPDATE_TIME = "last_update_time"

    private const val DEFAULT_LAST_UPDATE_TIME = 0L

    @JvmStatic
    fun getIsUpdateTime(): Boolean {
        val lastUpdateTime = Settings.getLong(KEY_LAST_UPDATE_TIME, DEFAULT_LAST_UPDATE_TIME)
        val now = Date()
        val nowTime = now.time
        val msNum = nowTime - lastUpdateTime
        val dayNum = msNum / (1000 * 60 * 60 * 24)
        return dayNum >= 1
    }

    @JvmStatic
    fun putUpdateTime(updateTime: Long) {
        Settings.putLong(KEY_LAST_UPDATE_TIME, updateTime)
    }
}
