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
import androidx.preference.Preference
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.LicenseActivity
import com.hippo.util.AppHelper

class AboutFragment : BasePreferenceFragmentCompat(),
    Preference.OnPreferenceClickListener {

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
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        val key = preference.key
        val activity = activity ?: return true

        if (KEY_AUTHOR == key) {
            AppHelper.sendEmail(
                activity, com.hippo.ehviewer.module.AppModule.getDeveloperEmail(),
                "About LR Reader", null
            )
        } else if (KEY_LICENSE == key) {
            activity.startActivity(Intent(activity, LicenseActivity::class.java))
        }
        return true
    }

    companion object {
        private const val KEY_AUTHOR = "author"
        private const val KEY_LICENSE = "license"
    }
}
