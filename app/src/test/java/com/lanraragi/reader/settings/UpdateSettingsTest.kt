package com.lanraragi.reader.settings

import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit test for the [UpdateSettings] auto-check default.
 * Robolectric-backed SharedPreferences (matches existing LRRAuthManagerTest pattern).
 *
 * Note: Settings has no remove() API, so setUp/tearDown call
 * Settings.getPreferences().edit().remove(KEY).apply() directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class UpdateSettingsTest {

    @Before
    fun setUp() {
        Settings.initialize(ApplicationProvider.getApplicationContext())
        Settings.getPreferences().edit().remove(UpdateSettings.KEY_AUTO_CHECK_UPDATES).apply()
    }

    @After
    fun tearDown() {
        Settings.getPreferences().edit().remove(UpdateSettings.KEY_AUTO_CHECK_UPDATES).apply()
    }

    @Test
    fun defaultIsTrue() {
        assertTrue(UpdateSettings.getAutoCheckUpdates())
    }
}
