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

import android.os.Bundle
import android.view.MenuItem
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.fragment.AdvancedFragment
import com.hippo.ehviewer.ui.fragment.SettingsHeaders
import com.hippo.util.DrawableManager

class SettingsActivity : EhActivity() {

    companion object {
        /** Optional extra naming a settings screen to open directly on launch. */
        const val KEY_INITIAL_SCREEN = "initial_screen"
        const val SCREEN_ADVANCED = "advanced"
    }

    override fun getThemeResId(theme: Int): Int = when (theme) {
        AppearanceSettings.THEME_DARK -> R.style.AppTheme_Settings_Dark
        AppearanceSettings.THEME_BLACK -> R.style.AppTheme_Settings_Black
        else -> R.style.AppTheme_Settings
    }

    private fun setActionBarUpIndicator() {
        val delegate = drawerToggleDelegate
        delegate?.setActionBarUpIndicator(
            DrawableManager.getVectorDrawable(this, R.drawable.v_arrow_left_dark_x24), 0
        )
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeButtonEnabled(true)
            setTitle(R.string.settings)
        }
    }

    @Suppress("deprecation")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setActionBarUpIndicator()
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsHeaders())
                .commit()
            // Deep-open a sub-screen on top of the headers so back still
            // returns to the headers list, mirroring normal navigation.
            if (intent.getStringExtra(KEY_INITIAL_SCREEN) == SCREEN_ADVANCED) {
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, AdvancedFragment())
                    .addToBackStack(null)
                    .commit()
                setSettingsTitle(R.string.settings_advanced)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun setSettingsTitle(res: Int) {
        supportActionBar?.let {
            it.setTitle(res)
            return
        }
        setTitle(res)
    }

    fun setSettingsTitle(res: CharSequence) {
        supportActionBar?.let {
            it.title = res
            return
        }
        title = res
    }
}
