package com.lanraragi.framework.lib.glgallery

import com.lanraragi.framework.lib.glview.util.GalleryUtils
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.util.minAllocatedBytes
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tier A rule: no per-frame allocation on the touch and render paths. A drag
 * posts a scroll event per touch move from the UI thread and the render thread
 * runs it at its next frame; both halves happen here on the test thread.
 * Before the fix every event boxed six Floats into a vararg array and every
 * frame copied the posted lists (about 140 B per move and 32 B per frame).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryViewGestureAllocationTest {

    private class FakeAdapter : GalleryView.Adapter() {
        override fun onBind(view: GalleryPageView, index: Int) {}
        override fun onUnbind(view: GalleryPageView, index: Int) {}
        override fun getError(): String? = null
        override fun size(): Int = 40
    }

    private val canvas = NoOpCanvas()
    private lateinit var view: GalleryView

    @Before
    fun setUp() {
        // The test thread plays both the UI thread and the GL render thread.
        GalleryUtils.setRenderThread()
        view = GalleryView.Builder(RuntimeEnvironment.getApplication(), FakeAdapter())
            .setLayoutMode(GalleryView.LAYOUT_RIGHT_TO_LEFT)
            .setStartPage(6)
            .setPagerInterval(PAGER_INTERVAL)
            .build()
        view.onAttachToRootInternal()
        view.measure(
            GLView.MeasureSpec.makeMeasureSpec(1080, GLView.MeasureSpec.EXACTLY),
            GLView.MeasureSpec.makeMeasureSpec(2400, GLView.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 2400)
        view.render(canvas)
    }

    @Test
    fun dragFramesAllocateNothing() {
        view.onDown(540f, 1200f)
        view.render(canvas)
        drag(FRAMES) // warm up
        val bytes = minAllocatedBytes(RUNS) { drag(FRAMES) }

        assertEquals("fewest bytes allocated by $FRAMES drag frames in $RUNS runs", 0L, bytes)
    }

    @Test
    fun pinchFramesAllocateNothing() {
        view.onDown(540f, 1000f)
        view.onPointerDown(540f, 1400f)
        view.onScaleBegin(540f, 1200f)
        view.render(canvas)
        pinch(FRAMES) // warm up
        val bytes = minAllocatedBytes(RUNS) { pinch(FRAMES) }

        assertEquals("fewest bytes allocated by $FRAMES pinch frames in $RUNS runs", 0L, bytes)
    }

    // A turn moves the page views between the previous, current and next
    // slots, recycles one through the page pool and binds another.
    @Test
    fun keyTurnsAllocateNothing() {
        keyTurns(FRAMES) // warm up
        val bytes = minAllocatedBytes(RUNS) { keyTurns(FRAMES) }

        assertEquals("fewest bytes allocated by $FRAMES key turns in $RUNS runs", 0L, bytes)
    }

    @Test
    fun dragTurnsAllocateNothing() {
        view.onDown(540f, 1200f)
        view.render(canvas)
        // Right-to-left, the finger moving right brings in the next page.
        dragTurns(1)
        assertEquals("the first drag frame turned the page", 7, currentIndex())
        view.onScroll(TURN_DX, 0f, TURN_DX, 0f, 540f - TURN_DX, 1200f)
        view.render(canvas)
        assertEquals("the second drag frame turned back", 6, currentIndex())
        dragTurns(FRAMES) // warm up
        val bytes = minAllocatedBytes(RUNS) { dragTurns(FRAMES) }

        assertEquals("fewest bytes allocated by $FRAMES drag turns in $RUNS runs", 0L, bytes)
    }

    /** The page under the screen centre (the turning frame leaves it pulled by a pixel). */
    private fun currentIndex(): Int =
        (6..7).first { view.findPageByIndex(it)?.bounds()?.contains(540, 1200) == true }

    private fun keyTurns(turns: Int) {
        for (i in 0 until turns) {
            if (i % 2 == 0) view.pageLeft() else view.pageRight()
            view.render(canvas)
        }
    }

    // Each move is longer than a page plus the pager interval, so every frame
    // turns the page, alternately forward and back.
    private fun dragTurns(turns: Int) {
        for (i in 0 until turns) {
            val dx = if (i % 2 == 0) -TURN_DX else TURN_DX
            view.onScroll(dx, 0f, dx, 0f, 540f - dx, 1200f)
            view.render(canvas)
        }
    }

    // Small moves back and forth: the page is pulled a little and returned, so
    // the scroll reaches the layout manager without ever turning the page.
    private fun drag(frames: Int) {
        for (i in 0 until frames) {
            val dx = if (i % 2 == 0) 6f else -6f
            view.onScroll(dx, 0f, dx, 0f, 540f - dx, 1200f)
            view.render(canvas)
        }
    }

    private fun pinch(frames: Int) {
        for (i in 0 until frames) {
            view.onScale(540f, 1200f, if (i % 2 == 0) 1.02f else 1f / 1.02f)
            view.render(canvas)
        }
    }

    private companion object {
        const val FRAMES = 200
        const val RUNS = 5

        const val PAGER_INTERVAL = 48

        // Page width 1080 plus the pager interval, plus one pixel.
        const val TURN_DX = 1080f + PAGER_INTERVAL + 1f
    }
}
