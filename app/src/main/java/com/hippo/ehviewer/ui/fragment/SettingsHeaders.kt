package com.hippo.ehviewer.ui.fragment

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.SettingsActivity

class SettingsHeaders : PreferenceFragmentCompat() {

    private var activity: SettingsActivity? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_headers, rootKey)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        super.onDisplayPreferenceDialog(preference)
    }

    override fun onNavigateToScreen(preferenceScreen: PreferenceScreen) {
        super.onNavigateToScreen(preferenceScreen)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (activity == null) {
            activity = getActivity() as? SettingsActivity
        }
        activity?.let { act ->
            val actionBar = act.supportActionBar
            if (actionBar != null) {
                actionBar.title = preference.title
            } else {
                act.title = preference.title
            }
        }
        preference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ -> false }
        return super.onPreferenceTreeClick(preference)
    }
}
