package com.lanraragi.reader.ui.gallery

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.Settings
import com.lanraragi.reader.settings.ReadingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The reader menu persists every setting before notifying GalleryActivity,
 * which recreates itself when fullscreen changed. The change used to be
 * detected in the Activity by reading the already-overwritten setting, so it
 * never fired and the toggle took effect only after reopening the reader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GalleryMenuHelperFullscreenTest {

    private val events = mutableListOf<String>()

    private val callback = object : GalleryMenuHelper.SettingsCallback {
        override fun onSettingsApplied(
            screenRotation: Int, layoutMode: Int, scaleMode: Int,
            startPosition: Int, keepScreenOn: Boolean,
            showClock: Boolean, showProgress: Boolean, showBattery: Boolean,
            showPageInterval: Boolean, volumePage: Boolean,
            reverseVolumePage: Boolean, readingFullscreen: Boolean,
            customScreenLightness: Boolean, screenLightness: Int,
            transferTime: Int
        ) {
            events += "applied fullscreen=$readingFullscreen"
        }

        override fun onReadingFullscreenChanged() {
            events += "fullscreen changed"
        }
    }

    private lateinit var helper: GalleryMenuHelper

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Settings.initialize(app)
        ReadingSettings.putReadingFullscreen(false)
        helper = GalleryMenuHelper(ContextThemeWrapper(app, R.style.AppTheme), callback, null)
        shadowOf(Looper.getMainLooper()).idle()
        events.clear()
    }

    private fun switch(id: Int): SwitchCompat = helper.view.findViewById(id)

    @Test
    fun `turning fullscreen on reports a fullscreen change`() {
        switch(R.id.reading_fullscreen).isChecked = true

        assertEquals(listOf("applied fullscreen=true", "fullscreen changed"), events)
        assertTrue(ReadingSettings.getReadingFullscreen())
    }

    @Test
    fun `turning fullscreen on then off reports a change each time`() {
        val fullscreen = switch(R.id.reading_fullscreen)
        fullscreen.isChecked = true
        fullscreen.isChecked = false

        assertEquals(
            listOf(
                "applied fullscreen=true", "fullscreen changed",
                "applied fullscreen=false", "fullscreen changed",
            ),
            events,
        )
    }

    @Test
    fun `changing another setting reports no fullscreen change`() {
        val keepScreenOn = switch(R.id.keep_screen_on)
        keepScreenOn.isChecked = !keepScreenOn.isChecked

        assertEquals(listOf("applied fullscreen=false"), events)
    }
}
