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

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.LicenseActivity
import com.hippo.ehviewer.updater.AppUpdater
import com.hippo.util.AppHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AboutFragment : BasePreferenceFragmentCompat(),
    Preference.OnPreferenceClickListener {

    private var checkUpdatePreference: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.about_settings)

        val author = findPreference<Preference>(KEY_AUTHOR)
        if (author != null) {
            author.summary = getString(R.string.settings_about_author_summary).replace('$', '@')
            author.onPreferenceClickListener = this
        }

        val license = findPreference<Preference>(KEY_LICENSE)
        if (license != null) {
            license.onPreferenceClickListener = this
        }

        checkUpdatePreference = findPreference<Preference>(KEY_CHECK_FOR_UPDATES)?.apply {
            onPreferenceClickListener = this@AboutFragment
        }
        // SwitchPreference at KEY_AUTO_CHECK_UPDATES is auto-wired by androidx Preference
        // to SharedPreferences (default true via android:defaultValue="true" in XML).
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        val key = preference.key
        val activity = activity ?: return true

        when (key) {
            KEY_AUTHOR -> {
                AppHelper.sendEmail(
                    activity,
                    com.hippo.ehviewer.module.AppModule.getDeveloperEmail(),
                    "About LR Reader",
                    null,
                )
            }
            KEY_LICENSE -> {
                activity.startActivity(Intent(activity, LicenseActivity::class.java))
            }
            KEY_CHECK_FOR_UPDATES -> {
                setCheckingState(true)
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    AppUpdater.update(activity, manualChecking = true)
                    // AppUpdater.update returns immediately after dispatching to IO;
                    // result UI (toast/dialog) is delivered asynchronously by the
                    // updater. We restore the preference state right away so the user
                    // can re-tap if the network call hangs (lock prevents duplicate
                    // in-flight requests anyway).
                    setCheckingState(false)
                }
            }
        }
        return true
    }

    private fun setCheckingState(checking: Boolean) {
        checkUpdatePreference?.apply {
            isEnabled = !checking
            summary = if (checking) {
                getString(R.string.settings_about_check_for_updates_in_progress)
            } else {
                getString(R.string.settings_about_check_for_updates_summary)
            }
        }
    }

    companion object {
        private const val KEY_AUTHOR = "author"
        private const val KEY_LICENSE = "license"
        private const val KEY_CHECK_FOR_UPDATES = "check_for_updates"
    }
}
