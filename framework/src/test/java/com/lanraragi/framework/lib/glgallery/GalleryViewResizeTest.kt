package com.lanraragi.framework.lib.glgallery

import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.lib.glview.util.GalleryUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Audit 2026-10-06b PERF-02 follow-up: with rotation handled in place, the
 * reader's GalleryView is re-measured and re-laid out at the new size, but
 * GalleryView.onLayout only ran fill() when a fill had been requested — so
 * the layout managers kept every page at the old (portrait) width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryViewResizeTest {

    private class FakeAdapter : GalleryView.Adapter() {
        override fun onBind(view: GalleryPageView, index: Int) {}
        override fun onUnbind(view: GalleryPageView, index: Int) {}
        override fun getError(): String? = null
        override fun size(): Int = 40
    }

    @Before
    fun setUp() {
        // The test thread plays the GL render thread.
        GalleryUtils.setRenderThread()
    }

    private fun attachedView(layoutMode: Int): GalleryView {
        val view = GalleryView.Builder(RuntimeEnvironment.getApplication(), FakeAdapter())
            .setLayoutMode(layoutMode)
            .setStartPage(6)
            .build()
        view.onAttachToRootInternal()
        return view
    }

    private fun layOut(view: GalleryView, width: Int, height: Int) {
        view.measure(
            GLView.MeasureSpec.makeMeasureSpec(width, GLView.MeasureSpec.EXACTLY),
            GLView.MeasureSpec.makeMeasureSpec(height, GLView.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun pageWidths(view: GalleryView): List<Int> =
        (0 until view.componentCount).map { view.getComponent(it) }
            .filterIsInstance<GalleryPageView>()
            .map { it.width }

    private fun assertPagesFollowRotation(layoutMode: Int) {
        val view = attachedView(layoutMode)
        layOut(view, 1080, 2400)
        val portrait = pageWidths(view)
        assertTrue("no page laid out in portrait", portrait.isNotEmpty())
        assertTrue("portrait pages $portrait", portrait.all { it == 1080 })

        layOut(view, 2400, 1080)
        val landscape = pageWidths(view)
        assertTrue("no page laid out in landscape", landscape.isNotEmpty())
        assertEquals(
            "pages kept their portrait width after the resize",
            List(landscape.size) { 2400 }, landscape,
        )
    }

    @Test
    fun `pager RTL pages are re-laid out to the new width`() {
        assertPagesFollowRotation(GalleryView.LAYOUT_RIGHT_TO_LEFT)
    }

    @Test
    fun `pager LTR pages are re-laid out to the new width`() {
        assertPagesFollowRotation(GalleryView.LAYOUT_LEFT_TO_RIGHT)
    }

    @Test
    fun `webtoon pages are re-laid out to the new width`() {
        assertPagesFollowRotation(GalleryView.LAYOUT_TOP_TO_BOTTOM)
    }
}
