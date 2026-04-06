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

package com.hippo.ehviewer.ui.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.util.LogCat
import com.hippo.util.ReadableTime
import java.io.File

class AdvancedFragment : BasePreferenceFragmentCompat(),
    Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.advanced_settings)

        val dumpLogcat = findPreference<Preference>(KEY_DUMP_LOGCAT)
        val clearMemoryCache = findPreference<Preference>(KEY_CLEAR_MEMORY_CACHE)
        val appLanguage = findPreference<Preference>(KEY_APP_LANGUAGE)

        dumpLogcat?.onPreferenceClickListener = this
        clearMemoryCache?.onPreferenceClickListener = this

        appLanguage?.onPreferenceChangeListener = this
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        return when (preference.key) {
            KEY_DUMP_LOGCAT -> dumpLogcat()
            KEY_CLEAR_MEMORY_CACHE -> clearMemoryCache()
            else -> false
        }
    }

    private fun clearMemoryCache(): Boolean {
        (activity!!.application as EhApplication).clearMemoryCache()
        Runtime.getRuntime().gc()
        return false
    }

    private fun dumpLogcat(): Boolean {
        var ok: Boolean
        var file: File? = null
        val dir = AppConfig.getExternalLogcatDir()
        if (dir != null) {
            file = File(dir, "logcat-" + ReadableTime.getFilenamableTime(System.currentTimeMillis()) + ".txt")
            ok = LogCat.save(file)
        } else {
            ok = false
        }
        val resources = resources
        Toast.makeText(
            activity,
            if (ok) resources.getString(R.string.settings_advanced_dump_logcat_to, file!!.path)
            else resources.getString(R.string.settings_advanced_dump_logcat_failed),
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val key = preference.key
        if (KEY_APP_LANGUAGE == key) {
            (activity!!.application as EhApplication).recreate()
            return true
        }
        return false
    }

    companion object {
        private const val KEY_DUMP_LOGCAT = "dump_logcat"
        private const val KEY_CLEAR_MEMORY_CACHE = "clear_memory_cache"
        private const val KEY_APP_LANGUAGE = "app_language"
    }
}
