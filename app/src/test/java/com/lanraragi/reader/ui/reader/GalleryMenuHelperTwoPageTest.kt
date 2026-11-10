package com.lanraragi.reader.ui.reader

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.reader.R
import com.lanraragi.reader.Settings
import com.lanraragi.reader.settings.ReadingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The two-page reading choices: off until the user turns them on (owner
 * decision P3), "first page alone" on, and what the reader dialog stores is
 * the spread mode the reader applies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GalleryMenuHelperTwoPageTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Settings.initialize(app)
        Settings.getPreferences().edit()
            .remove(ReadingSettings.KEY_SPREAD_READING)
            .remove(ReadingSettings.KEY_SPREAD_FIRST_PAGE_ALONE)
            .commit()
    }

    private fun menu(): GalleryMenuHelper {
        val callback = object : GalleryMenuHelper.SettingsCallback {
            override fun onSettingsApplied(
                screenRotation: Int, layoutMode: Int, scaleMode: Int,
                startPosition: Int, keepScreenOn: Boolean,
                showClock: Boolean, showProgress: Boolean, showBattery: Boolean,
                showPageInterval: Boolean, volumePage: Boolean,
                reverseVolumePage: Boolean, readingFullscreen: Boolean,
                customScreenLightness: Boolean, screenLightness: Int,
                transferTime: Int
            ) = Unit

            override fun onReadingFullscreenChanged() = Unit
        }
        val helper = GalleryMenuHelper(ContextThemeWrapper(app, R.style.AppTheme), callback, null)
        shadowOf(Looper.getMainLooper()).idle()
        return helper
    }

    @Test
    fun `two-page reading starts off, with the first page alone`() {
        assertEquals(GalleryView.SPREAD_OFF, ReadingSettings.getSpreadReading())
        assertTrue(ReadingSettings.getSpreadFirstPageAlone())
    }

    @Test
    fun `the dialog's choices are the spread modes the reader applies`() {
        val helper = menu()
        val twoPage = helper.view.findViewById<Spinner>(R.id.two_page)
        assertEquals(GalleryView.SPREAD_OFF, twoPage.selectedItemPosition)

        for (mode in listOf(GalleryView.SPREAD_LANDSCAPE, GalleryView.SPREAD_ALWAYS, GalleryView.SPREAD_OFF)) {
            twoPage.setSelection(mode)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(mode, ReadingSettings.getSpreadReading())
        }

        helper.view.findViewById<SwitchCompat>(R.id.first_page_alone).isChecked = false
        assertFalse(ReadingSettings.getSpreadFirstPageAlone())
    }

    @Test
    fun `a stored value the reader does not know reads as off`() {
        Settings.putIntToStr(ReadingSettings.KEY_SPREAD_READING, 7)

        assertEquals(GalleryView.SPREAD_OFF, ReadingSettings.getSpreadReading())
    }
}
