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

package com.hippo.ehviewer.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import com.hippo.content.ContextLocalWrapper
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.settings.SecuritySettings
import java.util.Locale

abstract class EhActivity : AppCompatActivity() {

    @StyleRes
    protected abstract fun getThemeResId(theme: Int): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply FLAG_SECURE before the window is created -- must be set before
        // super.onCreate() / setContentView() for reliable screenshot prevention.
        // See: WindowManager.LayoutParams.FLAG_SECURE documentation.
        if (SecuritySettings.getEnabledSecurity()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Extend content into display cutout (notch/punch-hole) areas.
        // Prevents white bars in landscape mode on devices with cutouts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setTheme(getThemeResId(AppearanceSettings.getTheme()))
        super.onCreate(savedInstanceState)

        (application as EhApplication).registerActivity(this)

        // Analytics stub (Firebase removed)
        @Suppress("UNUSED_EXPRESSION")
        Analytics.isEnabled
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as EhApplication).unregisterActivity(this)
    }

    override fun onResume() {
        super.onResume()
        // Re-check on resume to handle setting changes while app is running
        if (SecuritySettings.getEnabledSecurity()) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        var locale: Locale? = null
        val language = AppearanceSettings.getAppLanguage()
        if (language != null && language != "system") {
            val split = language.split("-")
            locale = when (split.size) {
                1 -> Locale(split[0])
                2 -> Locale(split[0], split[1])
                3 -> Locale(split[0], split[1], split[2])
                else -> null
            }
        }

        if (locale == null) {
            locale = Resources.getSystem().configuration.locale
        }
        val wrappedContext = ContextLocalWrapper.wrap(newBase, locale)
        super.attachBaseContext(wrappedContext)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (AppearanceSettings.isThemeAutoSwitchAvailable()) {
            val isDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if ((AppearanceSettings.getTheme() == 0) == isDark) {
                if (isDark) {
                    AppearanceSettings.putTheme(AppearanceSettings.THEME_DARK)
                } else {
                    AppearanceSettings.putTheme(AppearanceSettings.THEME_LIGHT)
                }
                (application as EhApplication).recreate()
            }
        }
    }
}
