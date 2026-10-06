package com.lanraragi.framework.drawable

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The search-bar and FAB icon morphs animate through the typed PROGRESS property
 * (audit 2026-10-06e STAB-02). A string-named "progress" animator worked here and in
 * debug builds but not in release, where R8 renamed setProgress; this test pins that
 * the typed property actually drives the drawable to the end shape. The release side
 * is enforced by scripts/ci/check-dex-animators.sh on the minified DEX.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MorphDrawableAnimationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test(timeout = 10_000)
    fun addDeleteDrawableAnimatesToDeleteAndBack() {
        val drawable = AddDeleteDrawable(context, Color.WHITE)

        drawable.setDelete(DURATION_MS)
        ShadowLooper.idleMainLooper(DURATION_MS * 2, TimeUnit.MILLISECONDS)
        assertEquals(1f, drawable.progress, 0f)

        drawable.setAdd(DURATION_MS)
        ShadowLooper.idleMainLooper(DURATION_MS * 2, TimeUnit.MILLISECONDS)
        assertEquals(0f, drawable.progress, 0f)
    }

    @Test(timeout = 10_000)
    fun drawerArrowDrawableAnimatesToArrowAndBack() {
        val drawable = DrawerArrowDrawable(context, Color.WHITE)

        drawable.setArrow(DURATION_MS)
        ShadowLooper.idleMainLooper(DURATION_MS * 2, TimeUnit.MILLISECONDS)
        assertEquals(1f, drawable.progress, 0f)

        drawable.setMenu(DURATION_MS)
        ShadowLooper.idleMainLooper(DURATION_MS * 2, TimeUnit.MILLISECONDS)
        assertEquals(0f, drawable.progress, 0f)
    }

    @Test
    fun progressPropertyReadsAndWritesTheDrawable() {
        val addDelete = AddDeleteDrawable(context, Color.WHITE)
        AddDeleteDrawable.PROGRESS.setValue(addDelete, 0.25f)
        assertEquals(0.25f, AddDeleteDrawable.PROGRESS.get(addDelete), 0f)

        val arrow = DrawerArrowDrawable(context, Color.WHITE)
        DrawerArrowDrawable.PROGRESS.setValue(arrow, 0.75f)
        assertEquals(0.75f, DrawerArrowDrawable.PROGRESS.get(arrow), 0f)
    }

    private companion object {
        const val DURATION_MS = 300L
    }
}
