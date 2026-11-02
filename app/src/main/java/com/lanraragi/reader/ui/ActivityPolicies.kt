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
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.settings.AppLockGate
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.settings.SecureWindowPolicy
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.util.LocaleContext

/**
 * An Activity that can lift the lock-only FLAG_SECURE once the app lock was
 * passed in it (SecurityScene calls this on its host).
 */
interface AppUnlockHost {
    fun onAppUnlocked()
}

/**
 * The app-wide Activity policies: secure window, display cutout, theme,
 * locale, the process-level app lock gate and theme auto-switch.
 *
 * Applied by [BaseActivity] and by [MainActivity], which extends the
 * framework's StageActivity (the framework knows nothing about the app, so
 * MainActivity applies these through StageActivity's lifecycle hooks, which
 * run at the same points BaseActivity's overrides do). Each method is called
 * from the matching lifecycle point; the comments say where.
 */
class ActivityPolicies(private val activity: AppCompatActivity) {

    /** Before `super.onCreate()`: window flags, then the theme. */
    fun beforeCreate(themeResId: (theme: Int) -> Int) {
        val window = activity.window
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

        activity.setTheme(themeResId(AppearanceSettings.getTheme()))
    }

    /**
     * Right after `super.onCreate()`. Process-level app lock: an Activity
     * created while the app is locked (restored after process death, or
     * launched directly by a widget / shortcut / notification) never shows
     * its content; it hands off to the lock screen and finishes. Callers must
     * return early from onCreate when isFinishing.
     */
    fun afterCreate(hostsLockScreen: Boolean, lockResumeIntent: () -> Intent?) {
        if (!hostsLockScreen && AppLockGate.isLocked()) {
            redirectToLockScreen(lockResumeIntent)
        }
    }

    /** After `super.onResume()`; [foregroundLockCheck] runs last. */
    fun onResume(foregroundLockCheck: () -> Unit) {
        // Re-check on resume to handle setting changes while app is running;
        // a locked app on API < 33 stays secure until the lock is passed.
        applySecureFlag(resumedAndUnlocked = !AppLockGate.isLocked())
        // With an app lock set, the recents thumbnail must not show the last
        // screen. Unlike FLAG_SECURE this keeps screenshots working.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!SecuritySettings.isLockEnabled())
        }
        foregroundLockCheck()
    }

    /** Before `super.onPause()`. */
    fun onPause() {
        // API < 33 with an app lock: hide the window before the recents
        // snapshot is taken (audit C48 / SEC-14).
        applySecureFlag(resumedAndUnlocked = false)
    }

    /** The lock was just passed in this resumed activity: lift the lock-only FLAG_SECURE. */
    fun onAppUnlocked() {
        applySecureFlag(resumedAndUnlocked = true)
    }

    /**
     * The default foreground lock check: while the app is locked, surface
     * MainActivity (the lock-screen host). MainActivity is `singleTask`, so
     * `FLAG_ACTIVITY_CLEAR_TOP` handles both topologies — this activity above
     * MainActivity in the same task (popped), or the root of its own task
     * (MainActivity's task is brought forward).
     */
    fun foregroundLockCheck(lockResumeIntent: () -> Intent?) {
        if (!AppLockGate.isLocked()) return
        redirectToLockScreen(lockResumeIntent)
    }

    /** After `super.onConfigurationChanged()`: follow the system dark mode. */
    fun onConfigurationChanged(newConfig: Configuration) {
        if (AppearanceSettings.isThemeAutoSwitchAvailable()) {
            val isDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (AppearanceSettings.syncThemeWithSystem(isDark)) {
                (activity.application as LRReaderApplication).recreate()
            }
        }
    }

    private fun redirectToLockScreen(lockResumeIntent: () -> Intent?) {
        lockResumeIntent()?.let { AppLockGate.stashResumeIntent(it) }
        activity.startActivity(
            Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        activity.finish()
    }

    private fun applySecureFlag(resumedAndUnlocked: Boolean) {
        val secure = SecureWindowPolicy.wantsSecureFlag(
            sdkInt = Build.VERSION.SDK_INT,
            userSecureMode = SecuritySettings.getEnabledSecurity(),
            lockEnabled = SecuritySettings.isLockEnabled(),
            resumedAndUnlocked = resumedAndUnlocked,
        )
        val window = activity.window
        if (secure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        /** In `attachBaseContext`: the context carrying the app's chosen locale. */
        @JvmStatic
        fun wrapBaseContext(newBase: Context): Context {
            val locale = AppearanceSettings.resolveAppLocale(AppearanceSettings.getAppLanguage())
            return LocaleContext.wrap(newBase, locale)
        }
    }
}
