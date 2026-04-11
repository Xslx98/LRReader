package com.hippo.ehviewer.ui.fragment

import android.os.Bundle
import androidx.preference.Preference
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.PrivacySettings
import com.hippo.ehviewer.settings.SecuritySettings

class PrivacyFragment : BasePreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.privacy_settings)
        findPreference<Preference>(PrivacySettings.KEY_ENABLE_ANALYTICS)?.onPreferenceChangeListener = this
    }

    override fun onResume() {
        super.onResume()
        findPreference<Preference>(KEY_PATTERN_PROTECTION)?.setSummary(
            if (!SecuritySettings.hasPattern()) {
                R.string.settings_privacy_pattern_protection_not_set
            } else {
                R.string.settings_privacy_pattern_protection_set
            }
        )
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val key = preference.key
        if (PrivacySettings.KEY_ENABLE_ANALYTICS == key) {
            if (newValue is Boolean && newValue) {
                activity?.let { Analytics.start(it) }
            }
            return true
        }
        return true
    }

    companion object {
        private const val KEY_PATTERN_PROTECTION = "pattern_protection"
    }
}
