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
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.lib.yorozuya.NumberUtils

object Settings {

    private const val TAG = "Settings"

    @SuppressLint("StaticFieldLeak") // Safe: holds Application Context, not Activity
    private lateinit var sContext: Context
    private lateinit var sSettingsPre: SharedPreferences
    @JvmStatic
    fun initialize(context: Context) {
        sContext = context.applicationContext
        sSettingsPre = PreferenceManager.getDefaultSharedPreferences(sContext)
        com.hippo.ehviewer.settings.DownloadSettings.initialize(sContext)
        if (AppearanceSettings.getDarkModeStatus(context) && AppearanceSettings.isThemeAutoSwitchAvailable()) {
            AppearanceSettings.putTheme(AppearanceSettings.THEME_DARK)
        }
    }

    /** Exposed for modular settings objects. */
    @JvmStatic
    fun getContext(): Context = sContext

    /** Exposed for modular settings objects that need batch editor access. */
    @JvmStatic
    fun getPreferences(): SharedPreferences = sSettingsPre

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

}
