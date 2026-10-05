package com.lanraragi.reader.widget

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RES-1: the drawer hands new window padding (e.g. the keyboard's height) to the stage
 * without re-laying it out, so the stage must request a layout itself or the keyboard
 * keeps covering the bottom of every scene.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStageLayoutInsetsTest {

    private fun laidOutStage(): AppStageLayout {
        val stage = AppStageLayout(ApplicationProvider.getApplicationContext())
        val spec = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        stage.measure(spec, spec)
        stage.layout(0, 0, 1000, 1000)
        assertFalse(stage.isLayoutRequested)
        return stage
    }

    @Test
    fun aChangedBottomPaddingRequestsALayout() {
        val stage = laidOutStage()

        stage.onGetWindowPadding(0, 880)

        assertTrue(stage.isLayoutRequested)
        assertEquals(880, stage.additionalBottomMargin)
    }

    @Test
    fun anUnchangedPaddingDoesNotRelayout() {
        val stage = laidOutStage()

        stage.onGetWindowPadding(0, 0)

        assertFalse(stage.isLayoutRequested)
    }
}
