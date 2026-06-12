package com.hippo.ehviewer.settings

import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down [AppearanceSettings.syncThemeWithSystem]: the user's chosen dark
 * variant (DARK vs BLACK) must survive light/dark round trips. The previous
 * three transition sites hardcoded THEME_DARK on the dark transition, so one
 * overnight light/dark cycle permanently downgraded a chosen BLACK to DARK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ThemeAutoSwitchTest {

    @Before
    fun setUp() {
        Settings.initialize(ApplicationProvider.getApplicationContext())
        clearKeys()
    }

    @After
    fun tearDown() {
        clearKeys()
    }

    private fun clearKeys() {
        Settings.getPreferences().edit()
            .remove(AppearanceSettings.KEY_THEME)
            .remove(AppearanceSettings.KEY_DARK_THEME_VARIANT)
            .apply()
    }

    @Test
    fun blackSurvivesLightDarkRoundTrip() {
        AppearanceSettings.putTheme(AppearanceSettings.THEME_BLACK)

        // System dark, app already dark: no change, variant captured.
        assertFalse(AppearanceSettings.syncThemeWithSystem(isSystemDark = true))
        assertEquals(AppearanceSettings.THEME_BLACK, AppearanceSettings.getTheme())

        // System goes light: app goes light, BLACK remembered.
        assertTrue(AppearanceSettings.syncThemeWithSystem(isSystemDark = false))
        assertEquals(AppearanceSettings.THEME_LIGHT, AppearanceSettings.getTheme())

        // System returns dark: BLACK restored, not DARK.
        assertTrue(AppearanceSettings.syncThemeWithSystem(isSystemDark = true))
        assertEquals(AppearanceSettings.THEME_BLACK, AppearanceSettings.getTheme())
    }

    @Test
    fun darkVariantDefaultsToDark() {
        AppearanceSettings.putTheme(AppearanceSettings.THEME_LIGHT)
        assertTrue(AppearanceSettings.syncThemeWithSystem(isSystemDark = true))
        assertEquals(AppearanceSettings.THEME_DARK, AppearanceSettings.getTheme())
    }

    @Test
    fun noChangeWhenAlreadyMatching() {
        AppearanceSettings.putTheme(AppearanceSettings.THEME_LIGHT)
        assertFalse(AppearanceSettings.syncThemeWithSystem(isSystemDark = false))
        assertEquals(AppearanceSettings.THEME_LIGHT, AppearanceSettings.getTheme())

        AppearanceSettings.putTheme(AppearanceSettings.THEME_DARK)
        assertFalse(AppearanceSettings.syncThemeWithSystem(isSystemDark = true))
        assertEquals(AppearanceSettings.THEME_DARK, AppearanceSettings.getTheme())
    }

    @Test
    fun manualDarkSelectionUpdatesVariantInPlace() {
        // User picks DARK, system dark: variant tracks DARK.
        AppearanceSettings.putTheme(AppearanceSettings.THEME_DARK)
        AppearanceSettings.syncThemeWithSystem(isSystemDark = true)
        assertEquals(AppearanceSettings.THEME_DARK, AppearanceSettings.getDarkThemeVariant())

        // User later switches to BLACK while still dark: variant follows.
        AppearanceSettings.putTheme(AppearanceSettings.THEME_BLACK)
        AppearanceSettings.syncThemeWithSystem(isSystemDark = true)
        assertEquals(AppearanceSettings.THEME_BLACK, AppearanceSettings.getDarkThemeVariant())
    }
}
