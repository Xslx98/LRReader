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

package com.lanraragi.reader.ui.fragment

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.backup.BackupController
import com.lanraragi.reader.diagnostics.DiagnosticsCollector
import com.lanraragi.reader.settings.AppearanceSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.IOException

class AdvancedFragment : BasePreferenceFragmentCompat(),
    Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

    private lateinit var backupController: BackupController

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.advanced_settings)

        backupController = BackupController(this)
        findPreference<Preference>(KEY_BACKUP_DATA)?.onPreferenceClickListener = this
        findPreference<Preference>(KEY_RESTORE_DATA)?.onPreferenceClickListener = this
        val shareDiagnostics = findPreference<Preference>(KEY_SHARE_DIAGNOSTICS)
        val clearMemoryCache = findPreference<Preference>(KEY_CLEAR_MEMORY_CACHE)
        val appLanguage = findPreference<Preference>(KEY_APP_LANGUAGE)

        shareDiagnostics?.onPreferenceClickListener = this
        clearMemoryCache?.onPreferenceClickListener = this

        appLanguage?.onPreferenceChangeListener = this
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        return when (preference.key) {
            KEY_SHARE_DIAGNOSTICS -> shareDiagnostics(preference)
            KEY_CLEAR_MEMORY_CACHE -> clearMemoryCache()
            KEY_BACKUP_DATA -> true.also { backupController.startBackup() }
            KEY_RESTORE_DATA -> true.also { backupController.startRestore() }
            else -> false
        }
    }

    private fun clearMemoryCache(): Boolean {
        (requireActivity().application as LRReaderApplication).clearMemoryCache()
        Runtime.getRuntime().gc()
        return false
    }

    /**
     * Zips the local reports, redacted events/logcat and a settings/queue summary
     * and hands it to the share sheet (audit 2026-10-04 C06, ruling R2). Nothing
     * leaves the device unless the user picks a target.
     */
    private fun shareDiagnostics(preference: Preference): Boolean {
        val context = requireContext().applicationContext
        val downloads = DiagnosticsCollector.downloadSummary()
        preference.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch(ServiceRegistry.coroutineModule.exceptionHandler) {
            val build = ServiceRegistry.coroutineModule.ioScope.async {
                try {
                    DiagnosticsCollector.build(context, downloads)
                } catch (e: IOException) {
                    Log.e(TAG, "Build diagnostics bundle", e)
                    null
                }
            }
            val file = try {
                build.await()
            } finally {
                preference.isEnabled = true
            }
            if (file == null) {
                Toast.makeText(context, R.string.settings_advanced_share_diagnostics_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val title = getString(R.string.settings_advanced_share_diagnostics)
            try {
                startActivity(DiagnosticsCollector.shareIntent(context, file, title))
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "No share target for diagnostics", e)
                Toast.makeText(context, R.string.settings_advanced_share_diagnostics_failed, Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val key = preference.key
        if (KEY_APP_LANGUAGE == key) {
            val language = newValue as? String ?: return false
            // A language change must refresh the application-context strings behind
            // GetText / notifications / services, which only re-resolve their locale
            // when attachBaseContext runs on a fresh process. An activity recreate()
            // cannot do that, so restart the whole process. Persist the new value
            // first (synchronously, batched with the route mark that brings the
            // fresh process back to this screen) so the fresh process reads it
            // in attachBaseContext.
            AppearanceSettings.putAppLanguageForRestart(language)
            (requireActivity().application as LRReaderApplication).restart()
            return false
        }
        return false
    }

    companion object {
        private const val TAG = "AdvancedFragment"
        private const val KEY_SHARE_DIAGNOSTICS = "share_diagnostics"
        private const val KEY_CLEAR_MEMORY_CACHE = "clear_memory_cache"
        private const val KEY_BACKUP_DATA = "backup_data"
        private const val KEY_RESTORE_DATA = "restore_data"
        private const val KEY_APP_LANGUAGE = "app_language"
    }
}
