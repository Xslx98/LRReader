package com.lanraragi.framework.lib.glview.image

import android.graphics.RectF
import com.lanraragi.framework.util.minAllocatedBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TileRegionsTest {

    private val out = RectF()

    @Test
    fun screenInTexture_mapsTheScreenThroughTheDrawRect() {
        // An 800x12000 strip drawn at half size (400x6000) in a view placed at
        // (0, -2000) in a 400x800 root: the screen covers view y 2000..2800,
        // which is texture y 4000..5600.
        TileRegions.screenInTexture(out, RectF(0f, 0f, 400f, 6000f), 0, -2000, 400, 800, 800, 12000)

        assertEquals(RectF(0f, 4000f, 800f, 5600f), out)
    }

    @Test
    fun screenInTexture_isNotClippedForAPageBelowTheScreen() {
        // The page starts one screen below the root: the screen lies above it.
        TileRegions.screenInTexture(out, RectF(0f, 0f, 400f, 6000f), 0, 800, 400, 800, 400, 6000)

        assertEquals(RectF(0f, -800f, 400f, 0f), out)
    }

    @Test
    fun screenInTexture_isEmptyBeforeLayout() {
        TileRegions.screenInTexture(out, RectF(), 0, 0, 400, 800, 400, 6000)

        assertTrue(out.isEmpty)
    }

    @Test
    fun screenInTexture_allocatesNothing() {
        // ImageView calls it on every frame that moves the page (drag, fling, pinch).
        val dst = RectF(-300f, -500f, 1300f, 1900f)
        screenInTextureFrames(dst) // warm up
        val bytes = minAllocatedBytes(5) { screenInTextureFrames(dst) }

        assertEquals("fewest bytes allocated by 200 calls in 5 runs", 0L, bytes)
    }

    private fun screenInTextureFrames(dst: RectF) {
        for (i in 0 until 200) {
            dst.offset(if (i % 2 == 0) 3f else -3f, 0f)
            TileRegions.screenInTexture(out, dst, 0, 0, 1080, 2400, 800, 1200)
        }
    }

    @Test
    fun band_growsByTheMarginOnEverySide() {
        TileRegions.band(out, RectF(0f, 1000f, 400f, 1800f), TileRegions.UPLOAD_MARGIN_SCREENS)

        assertEquals(RectF(-400f, 200f, 800f, 2600f), out)
    }

    @Test
    fun gap_isZeroWhenOverlappingAndGrowsWithDistance() {
        val screen = RectF(0f, 1000f, 400f, 1800f)

        assertEquals(0f, TileRegions.gap(screen, 0f, 1500f, 256f, 1756f), 0f)
        assertEquals(200f, TileRegions.gap(screen, 0f, 2000f, 256f, 2256f), 0f)
        assertEquals(744f, TileRegions.gap(screen, 0f, 0f, 256f, 256f), 0f)
    }
}
