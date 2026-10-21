package com.lanraragi.reader.ui.scene

import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.util.attrColor
import com.lanraragi.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RES-4 regression lock: the server-list row colors must resolve from the
 * ACTIVITY THEME (manual in-app theme setting), never from -night resources.
 * A theme missing one of these items would resolve to 0 — these assertions
 * fail the build if any of the three base themes loses an item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ServerListThemeColorsTest {

    private fun themed(styleRes: Int) = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(), styleRes
    )

    @Test
    fun activeRowBg_resolvesPerTheme() {
        assertEquals(
            0x1F009688,
            themed(R.style.AppTheme).attrColor(R.attr.serverActiveRowBg)
        )
        assertEquals(
            0x2600BFA5,
            themed(R.style.AppTheme_Dark).attrColor(R.attr.serverActiveRowBg)
        )
        assertEquals(
            0x2600BFA5,
            themed(R.style.AppTheme_Black).attrColor(R.attr.serverActiveRowBg)
        )
    }

    @Test
    fun activeNameColor_resolvesPerTheme_andPassesContrastChoices() {
        assertEquals(
            0xFF00796B.toInt(), // teal_700: 4.67:1 on white — AA
            themed(R.style.AppTheme).attrColor(R.attr.serverActiveNameColor)
        )
        assertEquals(
            0xFF00BFA5.toInt(), // teal_a700 on dark surfaces
            themed(R.style.AppTheme_Dark).attrColor(R.attr.serverActiveNameColor)
        )
        assertEquals(
            0xFF00BFA5.toInt(),
            themed(R.style.AppTheme_Black).attrColor(R.attr.serverActiveNameColor)
        )
    }

    @Test
    fun themeTextColors_resolveNonZero_andDifferAcrossLightDark() {
        val lightPrimary = themed(R.style.AppTheme).attrColor(android.R.attr.textColorPrimary)
        val darkPrimary = themed(R.style.AppTheme_Dark).attrColor(android.R.attr.textColorPrimary)
        assertNotEquals(0, lightPrimary)
        assertNotEquals(0, darkPrimary)
        assertNotEquals(
            "light and dark textColorPrimary must differ", lightPrimary, darkPrimary
        )
        val lightSecondary = themed(R.style.AppTheme).attrColor(android.R.attr.textColorSecondary)
        assertNotEquals(0, lightSecondary)
        assertNotEquals(
            "primary and secondary must differ in the light theme",
            lightPrimary, lightSecondary
        )
    }
}
