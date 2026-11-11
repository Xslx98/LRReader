package com.lanraragi.framework.lib.glgallery

import android.graphics.Rect
import android.view.ViewConfiguration
import com.lanraragi.framework.lib.glview.util.GalleryUtils
import com.lanraragi.framework.lib.glview.view.AnimationTime
import com.lanraragi.framework.lib.glview.view.GLView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The pager's page-turn contract as the reader sees it, in both reading
 * directions: which page is current, where its neighbours sit, what a key,
 * a tap zone, a drag or a jump turns to, what the ends do, and which page a
 * long press hits.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagerNavigationTest(private val layoutMode: Int) {

    private class FakeAdapter : GalleryView.Adapter() {
        val bound = mutableSetOf<GalleryPageView>()
        override fun onBind(view: GalleryPageView, index: Int) {
            // A page view starts out showing its error text, which takes taps.
            view.clearError()
            bound += view
        }
        override fun onUnbind(view: GalleryPageView, index: Int) {
            bound -= view
        }
        override fun getError(): String? = null
        override fun size(): Int = SIZE
    }

    private class RecordingListener : GalleryView.Listener {
        var currentIndex = GalleryPageView.INVALID_INDEX
        var longPressed = GalleryPageView.INVALID_INDEX
        var transferDone = 0
        override fun onUpdateCurrentIndex(index: Int) {
            currentIndex = index
        }
        override fun onTapSliderArea() {}
        override fun onTapMenuArea() {}
        override fun onTapErrorText(index: Int) {}
        override fun onLongPressPage(index: Int) {
            longPressed = index
        }
        override fun onPageTransformsChanged() {}
        override fun onAutoTransferDone() {
            transferDone++
        }
    }

    private val canvas = NoOpCanvas()
    private val adapter = FakeAdapter()
    private val listener = RecordingListener()
    private lateinit var view: GalleryView

    private val rtl get() = layoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT

    @Before
    fun setUp() {
        // The test thread plays both the UI thread and the GL render thread.
        GalleryUtils.setRenderThread()
        view = GalleryView.Builder(RuntimeEnvironment.getApplication(), adapter)
            .setListener(listener)
            .setLayoutMode(layoutMode)
            .setStartPage(START)
            .setPagerInterval(PAGER_INTERVAL)
            .build()
        view.onAttachToRootInternal()
        view.measure(
            GLView.MeasureSpec.makeMeasureSpec(WIDTH, GLView.MeasureSpec.EXACTLY),
            GLView.MeasureSpec.makeMeasureSpec(HEIGHT, GLView.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
        view.render(canvas)
    }

    /** Page to the left on screen: the next page right-to-left, the previous one left-to-right. */
    private fun leftOf(index: Int) = if (rtl) index + 1 else index - 1

    private fun rightOf(index: Int) = if (rtl) index - 1 else index + 1

    private fun assertShowing(index: Int) {
        assertEquals("current page", index, listener.currentIndex)
        val current = view.findPageByIndex(index)
        assertEquals("current page bounds", Rect(0, 0, WIDTH, HEIGHT), current?.bounds())
        val left = view.findPageByIndex(leftOf(index))
        if (leftOf(index) in 0 until SIZE) {
            assertTrue("page ${leftOf(index)} is left of $index", left != null && left.bounds().right <= 0)
        } else {
            assertNull("no page left of $index", left)
        }
        val right = view.findPageByIndex(rightOf(index))
        if (rightOf(index) in 0 until SIZE) {
            assertTrue("page ${rightOf(index)} is right of $index", right != null && right.bounds().left >= WIDTH)
        } else {
            assertNull("no page right of $index", right)
        }
        // Exactly the pages on screen and beside it are bound, nothing leaks.
        assertEquals("bound pages", listOfNotNull(current, left, right).toSet(), adapter.bound)
    }

    private fun keyNext() = if (rtl) view.pageLeft() else view.pageRight()

    private fun keyPrevious() = if (rtl) view.pageRight() else view.pageLeft()

    private fun tapNext() = tap(if (rtl) LEFT_ZONE_X else RIGHT_ZONE_X)

    private fun tapPrevious() = tap(if (rtl) RIGHT_ZONE_X else LEFT_ZONE_X)

    private fun tap(x: Float) {
        view.onSingleTapConfirmed(x, HEIGHT / 4f * 3f)
        view.render(canvas)
    }

    @Test
    fun `opens on the start page with its neighbours on either side`() {
        assertShowing(START)
        assertFalse(view.isReachEnd)
    }

    @Test
    fun `keys turn one page each way`() {
        keyNext()
        view.render(canvas)
        assertShowing(START + 1)

        keyPrevious()
        view.render(canvas)
        keyPrevious()
        view.render(canvas)
        assertShowing(START - 1)
    }

    @Test
    fun `tap zones turn one page each way`() {
        tapNext()
        assertShowing(START + 1)
        tapPrevious()
        tapPrevious()
        assertShowing(START - 1)
    }

    @Test
    fun `a drag past the interval settles on the page it reveals`() {
        // Finger moving right (negative scroll dx) reveals the page on the left.
        drag(-WIDTH / 2f)
        assertShowing(leftOf(START))
        drag(WIDTH / 2f)
        drag(WIDTH / 2f)
        assertShowing(rightOf(START))
    }

    @Test
    fun `a drag within the interval settles back`() {
        drag(-SHORT_DRAG)
        assertShowing(START)
        drag(SHORT_DRAG)
        assertShowing(START)
    }

    @Test
    fun `a drag past the whole page turns while the finger is down`() {
        view.onDown(WIDTH / 2f, HEIGHT / 2f)
        val dx = -(WIDTH + PAGER_INTERVAL + 1f)
        view.onScroll(dx, 0f, dx, 0f, WIDTH / 2f - dx, HEIGHT / 2f)
        view.render(canvas)
        assertEquals("current page", leftOf(START), listener.currentIndex)
        view.onUp()
        settle()
        assertShowing(leftOf(START))
    }

    private fun drag(dx: Float) {
        view.onDown(WIDTH / 2f, HEIGHT / 2f)
        view.onScroll(dx, 0f, dx, 0f, WIDTH / 2f - dx, HEIGHT / 2f)
        view.onUp()
        settle()
    }

    /** Runs the settle animation to its end on the Robolectric clock. */
    private fun settle() {
        repeat(SETTLE_FRAMES) {
            AnimationTime.update()
            view.render(canvas)
            ShadowSystemClock.advanceBy(Duration.ofMillis(SETTLE_FRAME_MS))
        }
        AnimationTime.update()
        view.render(canvas)
    }

    @Test
    fun `a jump to a neighbour turns one page`() {
        view.setCurrentPage(START + 1)
        view.render(canvas)
        assertShowing(START + 1)
        view.setCurrentPage(START)
        view.render(canvas)
        assertShowing(START)
    }

    @Test
    fun `a jump lands on the page and rebinds its neighbours`() {
        view.setCurrentPage(20)
        view.render(canvas)
        assertShowing(20)
    }

    @Test
    fun `the last page is the end and turning past it ends the transfer`() {
        view.setCurrentPage(SIZE - 1)
        view.render(canvas)
        assertShowing(SIZE - 1)
        assertTrue(view.isReachEnd)

        keyNext()
        view.render(canvas)
        assertShowing(SIZE - 1)
        assertEquals("transfer end", 1, listener.transferDone)
    }

    @Test
    fun `pulling past the last page ends the transfer`() {
        view.setCurrentPage(SIZE - 1)
        view.render(canvas)

        // The next page would come in from the left right-to-left: the finger moves right.
        val dx = if (rtl) -SHORT_DRAG else SHORT_DRAG
        view.onDown(WIDTH / 2f, HEIGHT / 2f)
        view.onScroll(dx, 0f, dx, 0f, WIDTH / 2f - dx, HEIGHT / 2f)
        view.onUp()
        settle()

        assertShowing(SIZE - 1)
        assertEquals("transfer end", 1, listener.transferDone)
    }

    /**
     * Issue #110: the end is decided on the whole pull, not on the last move
     * event. Each row is one gesture on the last page as finger moves (x
     * forward in reading order, y down) in pixels, and whether lifting the
     * finger ends the book.
     */
    @Test
    fun `lifting after pulling past the last page ends the book unless the pull came back`() {
        val slop = ViewConfiguration.get(RuntimeEnvironment.getApplication()).scaledTouchSlop.toFloat()
        // Moves back stay under the pager interval, past which they turn back a page.
        assertTrue(2.5f * slop < PAGER_INTERVAL)
        val rows = listOf(
            Gesture("one pull", listOf(3 * slop to 0f), true),
            Gesture("pull, then hold still with a tremor", listOf(2 * slop to 0f, 2 * slop to 0f, 0f to 1f), true),
            Gesture("pull, then a tiny drift back", listOf(2 * slop to 0f, 2 * slop to 0f, -2f to 0f), true),
            Gesture("a pull under the touch slop", listOf(slop / 2 to 0f), false),
            Gesture("pull, then back to the edge", listOf(2 * slop to 0f, -2 * slop to 0f), false),
            Gesture("pull, then back to within the slop", listOf(1.5f * slop to 0f, -slop to 0f), false),
            Gesture("pull, then back past the edge", listOf(slop to 0f, slop to 0f, -2.5f * slop to 0f), false),
        )
        for ((name, moves, ends) in rows) {
            view.setCurrentPage(SIZE - 1)
            view.render(canvas)
            listener.transferDone = 0

            pull(moves)

            assertShowing(SIZE - 1)
            assertEquals(name, if (ends) 1 else 0, listener.transferDone)
        }
    }

    private data class Gesture(val name: String, val moves: List<Pair<Float, Float>>, val ends: Boolean)

    @Test
    fun `a move back after pulling past the last page moves the page at once`() {
        view.setCurrentPage(SIZE - 1)
        view.render(canvas)
        val forward = if (rtl) -1f else 1f
        view.onDown(WIDTH / 2f, HEIGHT / 2f)
        for (dx in floatArrayOf(4 * SHORT_DRAG, -SHORT_DRAG)) {
            view.onScroll(forward * dx, 0f, forward * dx, 0f, WIDTH / 2f, HEIGHT / 2f)
            view.render(canvas)
        }
        val back = (forward * SHORT_DRAG).toInt()
        assertEquals("page moved back", Rect(back, 0, WIDTH + back, HEIGHT), view.findPageByIndex(SIZE - 1)?.bounds())
        view.onUp()
        settle()
    }

    /** One gesture; a move's x is positive towards the next page. */
    private fun pull(moves: List<Pair<Float, Float>>) {
        val forward = if (rtl) -1f else 1f
        view.onDown(WIDTH / 2f, HEIGHT / 2f)
        for ((dx, dy) in moves) {
            view.onScroll(forward * dx, dy, forward * dx, dy, WIDTH / 2f, HEIGHT / 2f)
            view.render(canvas)
        }
        view.onUp()
        settle()
    }

    @Test
    fun `turning back from the first page stays there`() {
        view.setCurrentPage(0)
        view.render(canvas)
        assertShowing(0)

        keyPrevious()
        view.render(canvas)
        assertShowing(0)
        assertEquals("transfer end", 0, listener.transferDone)
        assertFalse(view.isReachEnd)
    }

    @Test
    fun `a long press reports the page under the finger`() {
        view.onLongPress(WIDTH / 2f, HEIGHT / 2f)
        view.render(canvas)
        assertEquals(START, listener.longPressed)

        // Mid-drag the neighbour is partly on screen.
        view.onDown(WIDTH / 2f, HEIGHT / 2f)
        view.onScroll(-WIDTH / 2f, 0f, -WIDTH / 2f, 0f, WIDTH.toFloat(), HEIGHT / 2f)
        view.render(canvas)
        view.onLongPress(WIDTH / 4f, HEIGHT / 2f)
        view.render(canvas)
        assertEquals(leftOf(START), listener.longPressed)
    }

    companion object {
        private const val SIZE = 40
        private const val START = 6
        private const val WIDTH = 1080
        private const val HEIGHT = 2400
        private const val LEFT_ZONE_X = WIDTH / 6f
        private const val RIGHT_ZONE_X = WIDTH / 6f * 5f

        // Builder default; a release under it settles back, over it turns.
        private const val PAGER_INTERVAL = 48
        private const val SHORT_DRAG = PAGER_INTERVAL / 2f
        private const val SETTLE_FRAMES = 4
        private const val SETTLE_FRAME_MS = 500L

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "layoutMode={0}")
        fun modes(): List<Array<Any>> = listOf(
            arrayOf(GalleryView.LAYOUT_RIGHT_TO_LEFT),
            arrayOf(GalleryView.LAYOUT_LEFT_TO_RIGHT),
        )
    }
}
