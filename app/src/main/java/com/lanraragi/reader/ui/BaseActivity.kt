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

package com.lanraragi.reader.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import com.lanraragi.framework.content.ContextLocalWrapper
import com.lanraragi.reader.Analytics
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.settings.AppLockGate
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.settings.SecuritySettings

abstract class BaseActivity : AppCompatActivity() {

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

        // Process-level app lock: an Activity created while the app is
        // locked (restored after process death, or launched directly by a
        // widget / shortcut / notification) never shows its content; it
        // hands off to the lock screen and finishes. Subclasses must return
        // early from onCreate when isFinishing.
        if (!hostsLockScreen() && AppLockGate.isLocked()) {
            redirectToLockScreen()
        }

        // Analytics stub (Firebase removed)
        @Suppress("UNUSED_EXPRESSION")
        Analytics.isEnabled
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
        onForegroundLockCheck()
    }


    /**
     * True for the Activity that hosts the lock screen itself (MainActivity).
     * Every other Activity is redirected there while the app is locked.
     */
    protected open fun hostsLockScreen(): Boolean = false

    /**
     * The intent to relaunch once the lock is passed, or null to land on
     * MainActivity's current scene (GalleryActivity returns itself on its
     * current page).
     */
    protected open fun lockResumeIntent(): Intent? = null

    /**
     * Called from [onResume] after the secure-flag check: while the app is
     * locked, surface MainActivity (the lock-screen host). MainActivity is
     * `singleTask`, so `FLAG_ACTIVITY_CLEAR_TOP` handles both topologies —
     * this activity above MainActivity in the same task (popped), or the
     * root of its own task (MainActivity's task is brought forward).
     *
     * MainActivity overrides this to push SecurityScene directly.
     *
     * Subclasses that override [onResume] and run additional work after
     * `super.onResume()` should bail out via `if (isFinishing) return`.
     */
    protected open fun onForegroundLockCheck() {
        if (!AppLockGate.isLocked()) return
        redirectToLockScreen()
    }

    private fun redirectToLockScreen() {
        lockResumeIntent()?.let { AppLockGate.stashResumeIntent(it) }
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    override fun attachBaseContext(newBase: Context) {
        val locale = AppearanceSettings.resolveAppLocale(AppearanceSettings.getAppLanguage())
        super.attachBaseContext(ContextLocalWrapper.wrap(newBase, locale))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (AppearanceSettings.isThemeAutoSwitchAvailable()) {
            val isDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (AppearanceSettings.syncThemeWithSystem(isDark)) {
                (application as LRReaderApplication).recreate()
            }
        }
    }
}
