package com.lanraragi.reader.ui.scene.gallery.detail

import android.app.Activity
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lanraragi.reader.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The membership picker has a fixed height, so the soft keyboard covered
 * Cancel and Apply (#92): the keyboard's inset must become the sheet
 * content's bottom padding, and go away with the keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TankPickerSheetKeyboardTest {

    @Test
    fun theKeyboardInsetLiftsTheButtons_andHidingItDropsThePadding() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        TankPickerSheet(
            activity, "http://127.0.0.1:1", tanks = emptyList(), recentIds = emptyList(),
            suggestedName = null, mode = TankPickerSheet.Mode.Membership("a".repeat(40), mutableSetOf()) {},
            onCreate = {},
        ).show()
        val content = ShadowDialog.getLatestDialog().findViewById<View>(R.id.tank_picker_buttons).parent as View

        ViewCompat.dispatchApplyWindowInsets(content, imeInsets(bottom = 700))
        assertEquals(700, content.paddingBottom)

        ViewCompat.dispatchApplyWindowInsets(content, imeInsets(bottom = 0))
        assertEquals(0, content.paddingBottom)
    }

    private fun imeInsets(bottom: Int): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
        .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
        .build()
}
