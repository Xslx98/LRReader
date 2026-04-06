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

import android.app.Activity
import android.os.Bundle
import androidx.preference.Preference
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.AppearanceSettings

class EhFragment : BasePreferenceFragmentCompat(),
    Preference.OnPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.eh_settings)

        val theme = findPreference<Preference>(AppearanceSettings.KEY_THEME)
        val themeAutoSwitch = findPreference<Preference>(AppearanceSettings.KEY_THEME_AUTO_SWITCH)
        val applyNavBarThemeColor = findPreference<Preference>(AppearanceSettings.KEY_APPLY_NAV_BAR_THEME_COLOR)
        val listMode = findPreference<Preference>(AppearanceSettings.KEY_LIST_MODE)

        theme?.onPreferenceChangeListener = this
        themeAutoSwitch?.onPreferenceChangeListener = this
        applyNavBarThemeColor?.onPreferenceChangeListener = this
        listMode?.onPreferenceChangeListener = this
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val key = preference.key
        if (AppearanceSettings.KEY_THEME == key) {
            (activity!!.application as EhApplication).recreate()
            return true
        } else if (AppearanceSettings.KEY_APPLY_NAV_BAR_THEME_COLOR == key) {
            (activity!!.application as EhApplication).recreate()
            return true
        } else if (AppearanceSettings.KEY_LIST_MODE == key) {
            activity!!.setResult(Activity.RESULT_OK)
            return true
        } else if (AppearanceSettings.KEY_THEME_AUTO_SWITCH == key && java.lang.Boolean.TRUE == newValue) {
            if (AppearanceSettings.getDarkModeStatus(context!!)) {
                AppearanceSettings.putTheme(AppearanceSettings.THEME_DARK)
            } else {
                AppearanceSettings.putTheme(AppearanceSettings.THEME_LIGHT)
            }
            (activity!!.application as EhApplication).recreate()
            return true
        }
        return true
    }
}
