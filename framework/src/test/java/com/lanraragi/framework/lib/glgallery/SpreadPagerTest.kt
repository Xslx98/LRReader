package com.lanraragi.framework.lib.glgallery

import android.graphics.Rect
import com.lanraragi.framework.lib.glview.util.GalleryUtils
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.util.minAllocatedBytes
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

/**
 * Two-page spreads in the pager as the reader sees them, in both reading
 * directions: which pages share the screen, which half each is on, what a
 * key, tap or jump turns to, the ends, wide pages, re-planning when a page
 * turns out wide, and rotation. Indices stay page based throughout.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpreadPagerTest(private val layoutMode: Int) {

    private class FakeAdapter : GalleryView.Adapter() {
        val bound = mutableSetOf<GalleryPageView>()

        // Off in the allocation tests: the set itself allocates a node per bind.
        var record = true
        val aspects = FloatArray(SIZE) { PORTRAIT }

        // Tank member starts; an array, as a set would box the index.
        val breaks = BooleanArray(SIZE)
        override fun onBind(view: GalleryPageView, index: Int) {
            // A page view starts out showing its error text, which takes taps.
            view.clearError()
            if (record) bound += view
        }
        override fun onUnbind(view: GalleryPageView, index: Int) {
            if (record) bound -= view
        }
        override fun getError(): String? = null
        override fun size(): Int = SIZE
        override fun pageAspect(index: Int): Float = aspects[index]
        override fun isPairBreak(index: Int): Boolean = breaks[index]
    }

    private class RecordingListener : GalleryView.Listener {
        var currentIndex = GalleryPageView.INVALID_INDEX
        // Ints, not a string: the allocation tests count the listener too.
        var spreadFirst = -1
        var spreadLast = -1
        val spread get() = "$spreadFirst-$spreadLast"
        var longPressed = GalleryPageView.INVALID_INDEX
        var transferDone = 0
        override fun onUpdateCurrentIndex(index: Int) {
            currentIndex = index
        }
        override fun onUpdateCurrentSpread(first: Int, last: Int) {
            spreadFirst = first
            spreadLast = last
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
        adapter.aspects[WIDE_PAGE] = WIDE
        view = GalleryView.Builder(RuntimeEnvironment.getApplication(), adapter)
            .setListener(listener)
            .setLayoutMode(layoutMode)
            .setStartPage(START)
            .build()
        view.setSpreadMode(GalleryView.SPREAD_LANDSCAPE)
        view.onAttachToRootInternal()
        resize(WIDTH, HEIGHT)
    }

    private fun resize(width: Int, height: Int) {
        view.measure(
            GLView.MeasureSpec.makeMeasureSpec(width, GLView.MeasureSpec.EXACTLY),
            GLView.MeasureSpec.makeMeasureSpec(height, GLView.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        view.render(canvas)
    }

    /** The screen shows [first]..[last] filling the view, and the long press tells which half is which. */
    private fun assertShowing(first: Int, last: Int = first) {
        assertEquals("current page", first, listener.currentIndex)
        for (p in first..last) {
            assertEquals("page $p spans the screen", Rect(0, 0, WIDTH, HEIGHT), view.findPageByIndex(p)?.bounds())
        }
        // Right to left, the first page in reading order is on the right half.
        assertEquals("page under the right half", if (rtl) first else last, longPressAt(WIDTH * 3 / 4f))
        assertEquals("page under the left half", if (rtl) last else first, longPressAt(WIDTH / 4f))
        if (last > first) assertEquals("reported spread", "$first-$last", listener.spread)
    }

    private fun longPressAt(x: Float): Int {
        view.onLongPress(x, HEIGHT / 2f)
        view.render(canvas)
        return listener.longPressed
    }

    private fun keyNext() = render { if (rtl) view.pageLeft() else view.pageRight() }

    private fun keyPrevious() = render { if (rtl) view.pageRight() else view.pageLeft() }

    private fun tapNext() = render { view.onSingleTapConfirmed(if (rtl) LEFT_ZONE_X else RIGHT_ZONE_X, HEIGHT * 0.75f) }

    private fun tapPrevious() = render { view.onSingleTapConfirmed(if (rtl) RIGHT_ZONE_X else LEFT_ZONE_X, HEIGHT * 0.75f) }

    private fun jump(page: Int) = render { view.setCurrentPage(page) }

    private inline fun render(block: () -> Unit) {
        block()
        view.render(canvas)
    }

    @Test
    fun `opens on the natural spread holding the start page`() {
        // Cover alone, then 1-2, 3-4, 5-6: start page 6 shares 5-6.
        assertShowing(5, 6)
        assertNotNull(view.findPageByIndex(4))
        assertNotNull(view.findPageByIndex(7))
        assertEquals("both neighbour spreads bound, nothing leaks", 6, adapter.bound.size)
    }

    @Test
    fun `keys and tap zones turn a whole spread`() {
        keyNext()
        assertShowing(7, 8)
        tapNext()
        assertShowing(9, 10)
        keyPrevious()
        tapPrevious()
        keyPrevious()
        assertShowing(3, 4)
    }

    @Test
    fun `a jump lands on the spread holding the page`() {
        jump(20)
        assertShowing(19, 20)
        jump(19)
        assertShowing(19, 20)
        jump(1)
        assertShowing(1, 2)
        jump(0)
        assertShowing(0)
    }

    @Test
    fun `a wide page and the page before it stand alone`() {
        jump(WIDE_PAGE)
        assertShowing(WIDE_PAGE)
        keyPrevious()
        assertShowing(WIDE_PAGE - 1)
        keyNext()
        keyNext()
        assertShowing(WIDE_PAGE + 1, WIDE_PAGE + 2)
    }

    @Test
    fun `the end is the spread holding the last page`() {
        jump(SIZE - 1)
        assertShowing(SIZE - 2, SIZE - 1)
        assertTrue(view.isReachEnd)
        keyNext()
        assertShowing(SIZE - 2, SIZE - 1)
        assertEquals("transfer end", 1, listener.transferDone)

        keyPrevious()
        assertFalse(view.isReachEnd)
    }

    @Test
    fun `a partner found to be wide moves on, and the pages behind stay`() {
        adapter.aspects[6] = WIDE
        render { view.onPageAspectKnown(6) }
        assertShowing(5)
        keyNext()
        assertShowing(6)
        keyNext()
        assertShowing(7, 8)

        // Behind the reader: the previous spread is re-planned off screen,
        // the current one never moves.
        jump(20)
        adapter.aspects[18] = WIDE
        render { view.onPageAspectKnown(18) }
        assertShowing(19, 20)
        keyPrevious()
        assertShowing(18)
    }

    @Test
    fun `tank members start their own spreads, and the page a spread reports lands on it again`() {
        adapter.breaks[10] = true
        adapter.breaks[21] = true
        jump(0)
        val walked = ArrayList<String>()
        while (true) {
            val first = listener.currentIndex
            // A single page after a single page reports no spread at all.
            val last = if (listener.spreadFirst == first) listener.spreadLast else first
            walked += if (last > first) "$first-$last" else "$first"
            if (view.isReachEnd) break
            keyNext()
        }
        // Covers 10 and 21 alone, 9 and 20 alone before them, 11 alone before the wide 12.
        assertEquals(
            "0 1-2 3-4 5-6 7-8 9 10 11 12 13-14 15-16 17-18 19-20 21 22-23 24-25 26-27 28-29 30-31 32-33 34-35 " +
                "36-37 38",
            walked.joinToString(" "),
        )
        // Progress stores the page a spread reports; resuming there shows the same spread.
        for (shown in walked) {
            val first = shown.substringBefore('-').toInt()
            val last = shown.substringAfter('-').toInt()
            jump(if (first < SIZE / 2) SIZE - 1 else 0)
            jump(first)
            assertShowing(first, last)
        }
    }

    @Test
    fun `a tank remap keeps the page on screen and re-pairs around the moved member start`() {
        adapter.breaks[20] = true
        jump(23)
        assertShowing(23, 24)

        // The member before grew by three pages: its successor now starts at 23
        // and the page sizes learnt so far are forgotten.
        adapter.breaks[20] = false
        adapter.breaks[23] = true
        adapter.aspects.fill(SpreadPlanner.UNKNOWN_ASPECT)
        render { view.onDataChanged() }

        assertShowing(23)
        keyNext()
        assertShowing(24, 25)
        keyPrevious()
        keyPrevious()
        assertShowing(21, 22)
    }

    @Test
    fun `rotation keeps the first page and re-pairs it`() {
        resize(HEIGHT, WIDTH)
        assertEquals("portrait shows the first page alone", 5, listener.currentIndex)
        assertNull("no partner bound", view.findPageByIndex(6)?.takeIf { it.bounds().left == 0 })
        assertEquals("spread report ends with the single page", "5-5", listener.spread)
        resize(WIDTH, HEIGHT)
        assertShowing(5, 6)
    }

    @Test
    fun `first page alone and the book's shift decide how the cover pairs`() {
        render { view.setSpreadPairing(false, false, GalleryView.PARITY_UNKNOWN) }
        jump(0)
        assertShowing(0, 1)
        // The shift flips "first page alone" back, and re-pairs what is on screen.
        render { view.setSpreadPairing(false, true, GalleryView.PARITY_UNKNOWN) }
        assertShowing(0)
        render { view.setSpreadPairing(true, true, GalleryView.PARITY_UNKNOWN) }
        assertShowing(0, 1)
    }

    @Test
    fun `a stored parity repeats last time's pairs for one landing, and the screen reports its own`() {
        // A cache clear: no aspect is known, so without a parity the page
        // asked for would start its own spread.
        adapter.aspects.fill(SpreadPlanner.UNKNOWN_ASPECT)
        assertEquals(1, view.currentSpreadParity)
        render { view.setSpreadPairing(true, false, 0) }
        assertShowing(4, 5)
        assertEquals(0, view.currentSpreadParity)

        jump(9)
        assertShowing(9, 10)
        assertEquals(1, view.currentSpreadParity)

        resize(HEIGHT, WIDTH)
        assertEquals("single pages have no parity", GalleryView.PARITY_UNKNOWN, view.currentSpreadParity)
    }

    @Test
    fun `spread turns allocate nothing`() {
        adapter.record = false
        repeat(2) { keyNext() } // warm up
        val bytes = minAllocatedBytes(RUNS) {
            for (i in 0 until TURNS) {
                if (i % 2 == 0) keyNext() else keyPrevious()
            }
        }
        assertEquals("fewest bytes allocated by $TURNS spread turns in $RUNS runs", 0L, bytes)
    }

    @Test
    fun `dragging a spread allocates nothing`() {
        adapter.record = false
        view.onDown(WIDTH / 2f, HEIGHT / 2f)
        view.render(canvas)
        drag(TURNS) // warm up
        val bytes = minAllocatedBytes(RUNS) { drag(TURNS) }
        assertEquals("fewest bytes allocated by $TURNS drag frames in $RUNS runs", 0L, bytes)
    }

    private fun drag(frames: Int) {
        for (i in 0 until frames) {
            val dx = if (i % 2 == 0) 6f else -6f
            view.onScroll(dx, 0f, dx, 0f, WIDTH / 2f - dx, HEIGHT / 2f)
            view.render(canvas)
        }
    }

    private fun assertNotNull(page: GalleryPageView?) = org.junit.Assert.assertNotNull(page)

    companion object {
        private const val SIZE = 39
        private const val START = 6
        private const val WIDE_PAGE = 12
        private const val PORTRAIT = 0.7f
        private const val WIDE = 1.4f
        private const val WIDTH = 2400
        private const val HEIGHT = 1080
        private const val LEFT_ZONE_X = WIDTH / 6f
        private const val RIGHT_ZONE_X = WIDTH / 6f * 5f
        private const val TURNS = 200
        private const val RUNS = 5

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "layoutMode={0}")
        fun modes(): List<Array<Any>> = listOf(
            arrayOf(GalleryView.LAYOUT_RIGHT_TO_LEFT),
            arrayOf(GalleryView.LAYOUT_LEFT_TO_RIGHT),
        )
    }
}
