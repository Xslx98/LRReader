/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.settings.NetworkSettings
import com.hippo.lib.yorozuya.NumberUtils
import java.io.File
import java.util.Date
import java.util.Locale

object Settings {

    private const val TAG = "Settings"

    @SuppressLint("StaticFieldLeak") // Safe: holds Application Context, not Activity
    private lateinit var sContext: Context
    private lateinit var sSettingsPre: SharedPreferences
    private lateinit var sArchiverPre: SharedPreferences

    @JvmStatic
    fun initialize(context: Context) {
        sContext = context.applicationContext
        sSettingsPre = PreferenceManager.getDefaultSharedPreferences(sContext)
        sArchiverPre = context.getSharedPreferences("archiver_cache", Context.MODE_PRIVATE)
        if (AppearanceSettings.getDarkModeStatus(context) && AppearanceSettings.isThemeAutoSwitchAvailable()) {
            AppearanceSettings.putTheme(AppearanceSettings.THEME_DARK)
        }
        fixDefaultValue()
    }

    /** Exposed for modular settings objects. */
    @JvmStatic
    fun getContext(): Context = sContext

    /** Exposed for modular settings objects that need batch editor access. */
    @JvmStatic
    fun getPreferences(): SharedPreferences = sSettingsPre

    private fun fixDefaultValue() {
        // Enable builtin hosts if the country is CN
        if (!sSettingsPre.contains(NetworkSettings.KEY_BUILT_IN_HOSTS)) {
            if ("CN" == Locale.getDefault().country) {
                NetworkSettings.putBuiltInHosts(true)
                NetworkSettings.putBuiltEXHosts(true)
            }
        }
        if (!sSettingsPre.contains(NetworkSettings.KEY_DOMAIN_FRONTING)) {
            if ("CN" == Locale.getDefault().country) {
                NetworkSettings.putDF(true)
            }
        }
    }

    @JvmStatic
    fun getArchiverDownload(downloadId: Long): GalleryInfo? {
        val s = sArchiverPre.getString(downloadId.toString(), "") ?: ""
        if (s.isEmpty()) {
            return null
        }
        return try {
            GalleryInfo.galleryInfoFromJson(org.json.JSONObject(s))
        } catch (e: org.json.JSONException) {
            null
        }
    }

    @JvmStatic
    fun putArchiverDownload(downloadId: Long, info: GalleryInfo) {
        sArchiverPre.edit().putString(downloadId.toString(), info.toJson().toString()).apply()
    }

    @JvmStatic
    fun deleteArchiverDownload(downloadId: Long) {
        sArchiverPre.edit().remove(downloadId.toString()).apply()
    }

    @JvmStatic
    fun getArchiverDownloadId(gid: Long): Long {
        return sArchiverPre.getLong("${gid}DId", -1L)
    }

    @JvmStatic
    fun putArchiverDownloadId(gid: Long, downloadId: Long) {
        sArchiverPre.edit().putLong("${gid}DId", downloadId).apply()
    }

    @JvmStatic
    fun deleteArchiverDownloadId(gid: Long) {
        sArchiverPre.edit().remove("${gid}DId").apply()
    }

    @JvmStatic
    fun getBoolean(key: String, defValue: Boolean): Boolean {
        return try {
            sSettingsPre.getBoolean(key, defValue)
        } catch (e: ClassCastException) {
            Log.d(TAG, "Get ClassCastException when get $key value", e)
            defValue
        }
    }

    @JvmStatic
    fun putBoolean(key: String, value: Boolean) {
        sSettingsPre.edit().putBoolean(key, value).apply()
    }

    @JvmStatic
    fun getInt(key: String, defValue: Int): Int {
        return try {
            sSettingsPre.getInt(key, defValue)
        } catch (e: ClassCastException) {
            Log.d(TAG, "Get ClassCastException when get $key value", e)
            defValue
        }
    }

    @JvmStatic
    fun putInt(key: String, value: Int) {
        sSettingsPre.edit().putInt(key, value).apply()
    }

    @JvmStatic
    fun getLong(key: String, defValue: Long): Long {
        return try {
            sSettingsPre.getLong(key, defValue)
        } catch (e: ClassCastException) {
            Log.d(TAG, "Get ClassCastException when get $key value", e)
            defValue
        }
    }

    @JvmStatic
    fun putLong(key: String, value: Long) {
        sSettingsPre.edit().putLong(key, value).apply()
    }

    @JvmStatic
    fun getFloat(key: String, defValue: Float): Float {
        return try {
            sSettingsPre.getFloat(key, defValue)
        } catch (e: ClassCastException) {
            Log.d(TAG, "Get ClassCastException when get $key value", e)
            defValue
        }
    }

    @JvmStatic
    fun putFloat(key: String, value: Float) {
        sSettingsPre.edit().putFloat(key, value).apply()
    }

    @JvmStatic
    fun getString(key: String, defValue: String?): String? {
        return try {
            sSettingsPre.getString(key, defValue)
        } catch (e: ClassCastException) {
            Log.d(TAG, "Get ClassCastException when get $key value", e)
            defValue
        }
    }

    @JvmStatic
    fun putString(key: String, value: String?) {
        sSettingsPre.edit().putString(key, value).apply()
    }

    @JvmStatic
    fun getIntFromStr(key: String, defValue: Int): Int {
        return try {
            NumberUtils.parseIntSafely(sSettingsPre.getString(key, defValue.toString()), defValue)
        } catch (e: ClassCastException) {
            Log.d(TAG, "Get ClassCastException when get $key value", e)
            defValue
        }
    }

    @JvmStatic
    fun putIntToStr(key: String, value: Int) {
        sSettingsPre.edit().putString(key, value.toString()).apply()
    }

    private const val KEY_VERSION_CODE = "version_code"
    private const val DEFAULT_VERSION_CODE = 0

    @JvmStatic
    fun getVersionCode(): Int = getInt(KEY_VERSION_CODE, DEFAULT_VERSION_CODE)

    @JvmStatic
    fun putVersionCode(value: Int) = putInt(KEY_VERSION_CODE, value)

    private const val KEY_DISPLAY_NAME = "display_name"
    private val DEFAULT_DISPLAY_NAME: String? = null

    @JvmStatic
    fun getDisplayName(): String? = getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME)

    @JvmStatic
    fun putDisplayName(value: String?) = putString(KEY_DISPLAY_NAME, value)

    private const val KEY_AVATAR = "avatar"
    private val DEFAULT_AVATAR: String? = null

    @JvmStatic
    fun getAvatar(): String? = getString(KEY_AVATAR, DEFAULT_AVATAR)

    @JvmStatic
    fun putAvatar(value: String?) = putString(KEY_AVATAR, value)

    private const val KEY_REMOVE_IMAGE_FILES = "include_pic"
    private const val DEFAULT_REMOVE_IMAGE_FILES = true

    @JvmStatic
    fun getRemoveImageFiles(): Boolean = getBoolean(KEY_REMOVE_IMAGE_FILES, DEFAULT_REMOVE_IMAGE_FILES)

    @JvmStatic
    fun putRemoveImageFiles(value: Boolean) = putBoolean(KEY_REMOVE_IMAGE_FILES, value)

    private const val KEY_QUICK_SEARCH_TIP = "quick_search_tip"
    private const val DEFAULT_QUICK_SEARCH_TIP = true

    @JvmStatic
    fun getQuickSearchTip(): Boolean = getBoolean(KEY_QUICK_SEARCH_TIP, DEFAULT_QUICK_SEARCH_TIP)

    @JvmStatic
    fun putQuickSearchTip(value: Boolean) = putBoolean(KEY_QUICK_SEARCH_TIP, value)

    /********************
     ****** Eh
     ********************/
    // Theme, appearance, gallery site -> AppearanceSettings.kt

    // Launch page, list mode, thumb size, tag translations, categories,
    // excluded languages, cellular warning -> AppearanceSettings.kt / NetworkSettings.kt

    // Reading -> ReadingSettings.kt
    // Security -> SecuritySettings.kt
    // Download location, labels, preload -> DownloadSettings.kt
    // Favorites -> FavoritesSettings.kt

    /********************
     ****** Analytics
     ********************/
    private const val KEY_ASK_ANALYTICS = "ask_analytics"
    private const val DEFAULT_ASK_ANALYTICS = true

    @JvmStatic
    fun getAskAnalytics(): Boolean = getBoolean(KEY_ASK_ANALYTICS, DEFAULT_ASK_ANALYTICS)

    @JvmStatic
    fun putAskAnalytics(value: Boolean) = putBoolean(KEY_ASK_ANALYTICS, value)

    @JvmField
    val KEY_ENABLE_ANALYTICS = "enable_analytics"
    private const val DEFAULT_ENABLE_ANALYTICS = false

    @JvmStatic
    fun getEnableAnalytics(): Boolean = getBoolean(KEY_ENABLE_ANALYTICS, DEFAULT_ENABLE_ANALYTICS)

    @JvmStatic
    fun putEnableAnalytics(value: Boolean) = putBoolean(KEY_ENABLE_ANALYTICS, value)

    /********************
     ****** Update
     ********************/
    private const val KEY_BETA_UPDATE_CHANNEL = "beta_update_channel"
    private val DEFAULT_BETA_UPDATE_CHANNEL = EhApplication.BETA
    private const val KEY_SKIP_UPDATE_VERSION = "skip_update_version"
    private const val DEFAULT_SKIP_UPDATE_VERSION = 0

    @JvmStatic
    fun getBetaUpdateChannel(): Boolean = getBoolean(KEY_BETA_UPDATE_CHANNEL, DEFAULT_BETA_UPDATE_CHANNEL)

    @JvmStatic
    fun putBetaUpdateChannel(value: Boolean) = putBoolean(KEY_BETA_UPDATE_CHANNEL, value)

    @JvmStatic
    fun getSkipUpdateVersion(): Int = getInt(KEY_SKIP_UPDATE_VERSION, DEFAULT_SKIP_UPDATE_VERSION)

    @JvmStatic
    fun putSkipUpdateVersion(value: Int) = putInt(KEY_SKIP_UPDATE_VERSION, value)

    /********************
     ****** Advanced
     ********************/
    @JvmField
    val KEY_SAVE_PARSE_ERROR_BODY = "save_parse_error_body"
    private val DEFAULT_SAVE_PARSE_ERROR_BODY = EhApplication.BETA

    @JvmStatic
    fun getSaveParseErrorBody(): Boolean = getBoolean(KEY_SAVE_PARSE_ERROR_BODY, DEFAULT_SAVE_PARSE_ERROR_BODY)

    @JvmStatic
    fun putSaveParseErrorBody(value: Boolean) = putBoolean(KEY_SAVE_PARSE_ERROR_BODY, value)

    private const val KEY_SAVE_CRASH_LOG = "save_crash_log"
    private const val DEFAULT_SAVE_CRASH_LOG = false

    @JvmStatic
    fun getSaveCrashLog(): Boolean = getBoolean(KEY_SAVE_CRASH_LOG, DEFAULT_SAVE_CRASH_LOG)

    // Security (password, fingerprint) -> SecuritySettings.kt
    // Read cache size -> ReadingSettings.kt
    // Network (hosts, language, proxy) -> NetworkSettings.kt / AppearanceSettings.kt

    /********************
     ****** Guide
     ********************/
    private const val KEY_GUIDE_QUICK_SEARCH = "guide_quick_search"
    private const val DEFAULT_GUIDE_QUICK_SEARCH = true

    @JvmStatic
    fun getGuideQuickSearch(): Boolean = getBoolean(KEY_GUIDE_QUICK_SEARCH, DEFAULT_GUIDE_QUICK_SEARCH)

    @JvmStatic
    fun putGuideQuickSearch(value: Boolean) = putBoolean(KEY_GUIDE_QUICK_SEARCH, value)

    private const val KEY_GUIDE_COLLECTIONS = "guide_collections"
    private const val DEFAULT_GUIDE_COLLECTIONS = true

    @JvmStatic
    fun getGuideCollections(): Boolean = getBoolean(KEY_GUIDE_COLLECTIONS, DEFAULT_GUIDE_COLLECTIONS)

    @JvmStatic
    fun putGuideCollections(value: Boolean) = putBoolean(KEY_GUIDE_COLLECTIONS, value)

    private const val KEY_GUIDE_DOWNLOAD_THUMB = "guide_download_thumb"
    private const val DEFAULT_GUIDE_DOWNLOAD_THUMB = true

    @JvmStatic
    fun getGuideDownloadThumb(): Boolean = getBoolean(KEY_GUIDE_DOWNLOAD_THUMB, DEFAULT_GUIDE_DOWNLOAD_THUMB)

    @JvmStatic
    fun putGuideDownloadThumb(value: Boolean) = putBoolean(KEY_GUIDE_DOWNLOAD_THUMB, value)

    private const val KEY_GUIDE_DOWNLOAD_LABELS = "guide_download_labels"
    private const val DEFAULT_GUIDE_DOWNLOAD_LABELS = true

    @JvmStatic
    fun getGuideDownloadLabels(): Boolean = getBoolean(KEY_GUIDE_DOWNLOAD_LABELS, DEFAULT_GUIDE_DOWNLOAD_LABELS)

    @JvmStatic
    fun puttGuideDownloadLabels(value: Boolean) = putBoolean(KEY_GUIDE_DOWNLOAD_LABELS, value)

    private const val KEY_GUIDE_GALLERY = "guide_gallery"
    private const val DEFAULT_GUIDE_GALLERY = true

    @JvmStatic
    fun getGuideGallery(): Boolean = getBoolean(KEY_GUIDE_GALLERY, DEFAULT_GUIDE_GALLERY)

    @JvmStatic
    fun putGuideGallery(value: Boolean) = putBoolean(KEY_GUIDE_GALLERY, value)

    private const val KEY_CLIPBOARD_TEXT_HASH_CODE = "clipboard_text_hash_code"
    private const val DEFAULT_CLIPBOARD_TEXT_HASH_CODE = 0

    @JvmStatic
    fun getClipboardTextHashCode(): Int = getInt(KEY_CLIPBOARD_TEXT_HASH_CODE, DEFAULT_CLIPBOARD_TEXT_HASH_CODE)

    @JvmStatic
    fun putClipboardTextHashCode(value: Int) = putInt(KEY_CLIPBOARD_TEXT_HASH_CODE, value)

    // DNS-over-HTTPS, domain fronting -> NetworkSettings.kt
    // Download delay -> DownloadSettings.kt

    // Gallery comment, rating display -> AppearanceSettings.kt

    @JvmField
    val KEY_CLOSE_AUTO_UPDATES = "close_auto_updates"

    private var IS_CLOSE_AUTO_UPDATES = false

    @JvmStatic
    fun getCloseAutoUpdate(): Boolean = getBoolean(KEY_CLOSE_AUTO_UPDATES, IS_CLOSE_AUTO_UPDATES)

    @JvmStatic
    fun setKeyCloseAutoUpdates(value: Boolean) = putBoolean(KEY_CLOSE_AUTO_UPDATES, value)

    // Note: USER_BACKGROUND_IMAGE/USER_AVATAR_IMAGE are still used directly
    @JvmField
    val USER_BACKGROUND_IMAGE = "background_image_path"
    @JvmField
    val USER_AVATAR_IMAGE = "avatar_image_path"

    @JvmStatic
    fun getUserImageFile(key: String): File? {
        val path = getString(key, "") ?: ""
        if (path.isEmpty()) {
            return null
        }
        val file = File(path)
        return if (file.exists()) file else null
    }

    @JvmStatic
    fun saveFilePath(key: String, path: String?) {
        putString(key, path)
    }

    // Download order, pagination, drag, timeout -> DownloadSettings.kt
    // Read progress display -> ReadingSettings.kt
    // History info size -> AppearanceSettings.kt

    @JvmField
    val KEY_LAST_UPDATE_TIME = "last_update_time"

    @JvmField
    var DEFAULT_LAST_UPDATE_TIME = 0L

    @JvmStatic
    fun getIsUpdateTime(): Boolean {
        val lastUpdateTime = getLong(KEY_LAST_UPDATE_TIME, DEFAULT_LAST_UPDATE_TIME)
        val now = Date()
        val nowTime = now.time
        val msNum = nowTime - lastUpdateTime
        val dayNum = msNum / (1000 * 60 * 60 * 24)
        return dayNum >= 1
    }

    @JvmStatic
    fun putUpdateTime(updateTime: Long) {
        putLong(KEY_LAST_UPDATE_TIME, updateTime)
    }
}
