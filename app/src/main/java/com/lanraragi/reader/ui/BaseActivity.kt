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
import android.os.Bundle
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity

/**
 * The app's Activity base: applies [ActivityPolicies] at its lifecycle points.
 * MainActivity extends the framework's StageActivity instead and applies the
 * same policies through StageActivity's hooks.
 */
abstract class BaseActivity : AppCompatActivity(), AppUnlockHost {

    private val policies = ActivityPolicies(this)

    @StyleRes
    protected abstract fun getThemeResId(theme: Int): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        policies.beforeCreate(::getThemeResId)
        super.onCreate(savedInstanceState)
        // Subclasses must return early from onCreate when isFinishing.
        // MainActivity, the lock-screen host, is not a BaseActivity.
        policies.afterCreate(hostsLockScreen = false, lockResumeIntent = ::lockResumeIntent)
    }

    override fun onResume() {
        super.onResume()
        policies.onResume(::onForegroundLockCheck)
    }

    override fun onPause() {
        policies.onPause()
        super.onPause()
    }

    /** The lock was just passed in this resumed activity: lift the lock-only FLAG_SECURE. */
    override fun onAppUnlocked() {
        policies.onAppUnlocked()
    }

    /**
     * The intent to relaunch once the lock is passed, or null to land on
     * MainActivity's current scene (GalleryActivity returns itself on its
     * current page).
     */
    protected open fun lockResumeIntent(): Intent? = null

    /**
     * Called from [onResume] after the secure-flag check: while the app is
     * locked, surface MainActivity (the lock-screen host); see
     * [ActivityPolicies.foregroundLockCheck].
     *
     * Subclasses that override [onResume] and run additional work after
     * `super.onResume()` should bail out via `if (isFinishing) return`.
     */
    protected open fun onForegroundLockCheck() {
        policies.foregroundLockCheck(::lockResumeIntent)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ActivityPolicies.wrapBaseContext(newBase))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        policies.onConfigurationChanged(newConfig)
    }
}
