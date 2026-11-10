package com.lanraragi.framework.lib.glgallery

import android.graphics.RectF
import com.lanraragi.framework.util.minAllocatedBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The joint viewport of a two-page spread: where each page sits at joint
 * fit, and that zoom and pan move both pages as one image across the seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class SpreadViewportTest {

    private val viewport = SpreadViewport()
    private val left = RectF()
    private val right = RectF()
    private val remain = IntArray(2)

    private fun pages(leftW: Int, leftH: Int, rightW: Int, rightH: Int) {
        viewport.setPage(SpreadViewport.LEFT, leftW, leftH)
        viewport.setPage(SpreadViewport.RIGHT, rightW, rightH)
        viewport.layout(W, H)
        read()
    }

    private fun read() {
        viewport.dst(SpreadViewport.LEFT, left)
        viewport.dst(SpreadViewport.RIGHT, right)
    }

    private fun assertRect(name: String, expected: RectF, actual: RectF) {
        assertEquals("$name left", expected.left, actual.left, EPS)
        assertEquals("$name top", expected.top, actual.top, EPS)
        assertEquals("$name right", expected.right, actual.right, EPS)
        assertEquals("$name bottom", expected.bottom, actual.bottom, EPS)
    }

    @Test
    fun `at joint fit each page fits its half and they meet at the centre`() {
        // 700x1000 in a 1200x1080 half: height-limited, scale 1.08, 756 wide.
        pages(700, 1000, 700, 1000)
        assertRect("left", RectF(444f, 0f, 1200f, 1080f), left)
        assertRect("right", RectF(1200f, 0f, 1956f, 1080f), right)
        assertFalse("nothing to pan at fit", viewport.canFling())
    }

    @Test
    fun `pages of different widths still meet, and the pair is centred`() {
        // Right page square: 1080 wide; union 444..2280 (1836) centred -> shift -162.
        pages(700, 1000, 1000, 1000)
        assertEquals("seam", left.right, right.left, EPS)
        assertEquals("left margin", W - right.right, left.left, EPS)
        assertEquals(282f, left.left, EPS)
    }

    @Test
    fun `a page without an image borrows its partner's shape`() {
        pages(700, 1000, 0, 0)
        assertEquals("same width", left.width(), right.width(), EPS)
        assertEquals("seam", left.right, right.left, EPS)
        assertTrue(viewport.isLoaded)
    }

    @Test
    fun `zoom scales both pages around the focus and a pan crosses the seam`() {
        pages(700, 1000, 700, 1000)
        viewport.scale(W / 2f, H / 2f, 2f)
        read()
        assertEquals("seam stays under the focus", W / 2f, left.right, EPS)
        assertEquals("both pages doubled", 1512f, left.width(), EPS)
        assertEquals(left.width(), right.width(), EPS)

        // Union 3024 wide in 2400: 312 px each side to pan. Panning right
        // brings the right page across the seam position.
        viewport.scroll(200, 0, remain)
        read()
        assertEquals("took the whole move", 0, remain[0])
        assertEquals(W / 2f - 200f, left.right, EPS)
        assertEquals("pages still meet", left.right, right.left, EPS)

        viewport.scroll(500, 0, remain)
        read()
        assertEquals("stopped at the union's edge", 388, remain[0])
        assertEquals(W.toFloat(), right.right, EPS)
        assertEquals("fling room back to the left edge", 624, viewport.getMaxDx())
        assertEquals("none further right", 0, viewport.getMinDx())
    }

    @Test
    fun `zoom never goes below joint fit`() {
        pages(700, 1000, 700, 1000)
        viewport.scale(W / 2f, H / 2f, 0.5f)
        read()
        assertEquals(1f, viewport.scale, 0f)
        assertRect("left", RectF(444f, 0f, 1200f, 1080f), left)
    }

    @Test
    fun `double tap goes from joint fit to one page's width across the slot`() {
        pages(700, 1000, 700, 1000)
        val steps = FloatArray(4)
        viewport.getScaleDefault(steps)
        assertEquals(1f, steps[0], 0f)
        assertEquals(W / 756f, steps[1], EPS)
        assertEquals(steps[1], steps[3], 0f)
    }

    @Test
    fun `the side under a point is decided by the seam`() {
        pages(700, 1000, 700, 1000)
        assertEquals(SpreadViewport.LEFT, viewport.sideAt(100f))
        assertEquals(SpreadViewport.LEFT, viewport.sideAt(1199f))
        assertEquals(SpreadViewport.RIGHT, viewport.sideAt(1200f))
        viewport.scale(W / 2f, H / 2f, 2f)
        viewport.scroll(200, 0, remain)
        assertEquals("the seam moved left with the pan", SpreadViewport.RIGHT, viewport.sideAt(1100f))
    }

    @Test
    fun `zoom and pan allocate nothing`() {
        pages(700, 1000, 700, 1000)
        val bytes = minAllocatedBytes(RUNS) {
            for (i in 0 until FRAMES) {
                viewport.scale(W / 2f, H / 2f, if (i % 2 == 0) 1.5f else 1f / 1.5f)
                viewport.scroll(if (i % 2 == 0) 30 else -30, 0, remain)
                viewport.dst(SpreadViewport.LEFT, left)
                viewport.layout(W, H)
            }
        }
        assertEquals(0L, bytes)
    }

    private companion object {
        const val W = 2400
        const val H = 1080
        const val EPS = 0.01f
        const val FRAMES = 200
        const val RUNS = 5
    }
}
