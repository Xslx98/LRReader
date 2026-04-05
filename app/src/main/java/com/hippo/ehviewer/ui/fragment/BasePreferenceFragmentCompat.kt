package com.hippo.ehviewer.ui.fragment

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.SettingsActivity

open class BasePreferenceFragmentCompat : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // No-op; subclasses override
    }

    private fun setBaseStyle(preference: Preference) {
        preference.isIconSpaceReserved = false
        if (preference is PreferenceGroup) {
            for (i in 0 until preference.preferenceCount) {
                setBaseStyle(preference.getPreference(i))
            }
        }
    }

    override fun setPreferenceScreen(preferenceScreen: PreferenceScreen?) {
        if (preferenceScreen != null) {
            setBaseStyle(preferenceScreen)
        }
        super.setPreferenceScreen(preferenceScreen)
    }

    override fun onDestroyView() {
        val activity = activity ?: return
        (activity as SettingsActivity).setSettingsTitle(R.string.settings)
        super.onDestroyView()
    }
}
