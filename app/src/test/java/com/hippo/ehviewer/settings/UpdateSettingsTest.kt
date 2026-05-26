package com.hippo.ehviewer.settings

import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.Settings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [UpdateSettings] auto-check getter/setter + default behavior.
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

    @Test
    fun setterAndGetterRoundTrip() {
        UpdateSettings.putAutoCheckUpdates(false)
        assertFalse(UpdateSettings.getAutoCheckUpdates())

        UpdateSettings.putAutoCheckUpdates(true)
        assertTrue(UpdateSettings.getAutoCheckUpdates())
    }

    @Test
    fun setterPersistsAcrossGets() {
        UpdateSettings.putAutoCheckUpdates(false)
        // Read twice to ensure value comes from SharedPreferences, not a memoized cache
        assertFalse(UpdateSettings.getAutoCheckUpdates())
        assertFalse(UpdateSettings.getAutoCheckUpdates())
    }
}
