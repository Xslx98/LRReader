package com.lanraragi.framework.widget

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The indeterminate spinner animates its four trim values through typed properties
 * instead of string names resolved by reflection (audit 2026-10-06e STAB-02); this
 * pins that every property still reaches its setter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProgressViewAnimationTest {

    @Test(timeout = 10_000)
    fun indeterminateAnimatorsDriveAllTrimValues() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = ProgressView(activity, null)
        activity.setContentView(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(SIZE_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(SIZE_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, SIZE_PX, SIZE_PX)
        ShadowLooper.idleMainLooper()

        // The animators start on the first draw after attach.
        view.draw(Canvas(Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)))
        ShadowLooper.idleMainLooper(ADVANCE_MS, TimeUnit.MILLISECONDS)

        assertTrue("trimStart ${view.trimStart}", view.trimStart > 0f)
        assertTrue("trimEnd ${view.trimEnd}", view.trimEnd > 0f)
        assertTrue("trimOffset ${view.trimOffset}", view.trimOffset > 0f)
        assertTrue("trimRotation ${view.trimRotation}", view.trimRotation > 0f)
    }

    private companion object {
        const val SIZE_PX = 48
        const val ADVANCE_MS = 1000L
    }
}
