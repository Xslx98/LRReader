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

package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.settings.AppearanceSettings;

public class EhFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.eh_settings);

        Preference theme = findPreference(AppearanceSettings.KEY_THEME);
        Preference themeAutoSwitch = findPreference(AppearanceSettings.KEY_THEME_AUTO_SWITCH);
        Preference applyNavBarThemeColor = findPreference(AppearanceSettings.KEY_APPLY_NAV_BAR_THEME_COLOR);
        Preference listMode = findPreference(AppearanceSettings.KEY_LIST_MODE);

        if (theme != null) theme.setOnPreferenceChangeListener(this);
        if (themeAutoSwitch != null) themeAutoSwitch.setOnPreferenceChangeListener(this);
        if (applyNavBarThemeColor != null) applyNavBarThemeColor.setOnPreferenceChangeListener(this);
        if (listMode != null) listMode.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (AppearanceSettings.KEY_THEME.equals(key)) {
            ((EhApplication) getActivity().getApplication()).recreate();
            return true;
        } else if (AppearanceSettings.KEY_APPLY_NAV_BAR_THEME_COLOR.equals(key)) {
            ((EhApplication) getActivity().getApplication()).recreate();
            return true;
        } else if (AppearanceSettings.KEY_LIST_MODE.equals(key)) {
            getActivity().setResult(Activity.RESULT_OK);
            return true;
        } else if (AppearanceSettings.KEY_THEME_AUTO_SWITCH.equals(key) && Boolean.TRUE.equals(newValue)) {
            if (AppearanceSettings.getDarkModeStatus(getContext())) {
                AppearanceSettings.putTheme(AppearanceSettings.THEME_DARK);
            } else {
                AppearanceSettings.putTheme(AppearanceSettings.THEME_LIGHT);
            }
            ((EhApplication) getActivity().getApplication()).recreate();
            return true;
        }
        return true;
    }
}
