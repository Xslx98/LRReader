package com.lanraragi.reader.util

import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ThemeAttrsTest {

    private fun themed(styleRes: Int) = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(), styleRes
    )

    @Test
    fun isLightTheme_followsTheAppThemes() {
        assertTrue(themed(R.style.AppTheme).isLightTheme())
        assertFalse(themed(R.style.AppTheme_Dark).isLightTheme())
        assertFalse(themed(R.style.AppTheme_Black).isLightTheme())
    }

    @Test
    fun attrColor_resolvesAColourStateListToItsDefaultColour() {
        val context = themed(R.style.AppTheme)
        // textColorPrimary is a selector, not a plain colour.
        val expected = context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            .run { getColorStateList(0)!!.defaultColor.also { recycle() } }
        assertEquals(expected, context.attrColor(android.R.attr.textColorPrimary))
    }

    @Test(expected = IllegalArgumentException::class)
    fun attrColor_throwsForAnUndefinedAttribute() {
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), android.R.style.Theme)
            .attrColor(R.attr.serverActiveRowBg)
    }

    @Test
    fun attrDrawable_resolvesTheWindowBackground() {
        val drawable = themed(R.style.AppTheme).attrDrawable(android.R.attr.windowBackground)
        assertNotNull(drawable)
    }

    @Test
    fun attrDrawable_wrapsAPlainColour() {
        val drawable = themed(R.style.AppTheme).attrDrawable(R.attr.serverActiveRowBg)
        assertEquals(0x1F009688, (drawable as ColorDrawable).color)
    }

    @Test(expected = Resources.NotFoundException::class)
    fun attrDrawable_throwsForAnUndefinedAttribute() {
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), android.R.style.Theme)
            .attrDrawable(R.attr.serverActiveRowBg)
    }
}
