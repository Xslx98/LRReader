package com.lanraragi.framework.lib.glgallery

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import com.lanraragi.framework.lib.glview.util.GalleryUtils
import com.lanraragi.framework.lib.glview.view.GLRootView
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.lib.image.Image
import com.lanraragi.framework.lib.glview.image.ImageWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A reader page cell shows one [PageCellState]; the adapter renders every provider event through it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageCellStateTest {

    private class TestProvider : GalleryProvider(cacheMaxBytes = 1 shl 20) {
        override fun size(): Int = 5
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getError(): String? = null
    }

    private val canvas = NoOpCanvas()
    private lateinit var adapter: SimpleAdapter
    private lateinit var view: GalleryView

    @Before
    fun setUp() {
        // The test thread plays both the UI thread and the GL render thread.
        GalleryUtils.setRenderThread()
        val app = RuntimeEnvironment.getApplication()
        adapter = SimpleAdapter(GLRootView(app), TestProvider())
        view = GalleryView.Builder(app, adapter)
            .setLayoutMode(GalleryView.LAYOUT_LEFT_TO_RIGHT)
            .setStartPage(0)
            .build()
        view.onAttachToRootInternal()
        view.measure(
            GLView.MeasureSpec.makeMeasureSpec(WIDTH, GLView.MeasureSpec.EXACTLY),
            GLView.MeasureSpec.makeMeasureSpec(HEIGHT, GLView.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
        view.render(canvas)
    }

    private fun cell(index: Int = 0): GalleryPageView = view.findPageByIndex(index)!!

    private fun image(): ImageWrapper {
        val bitmap = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        return ImageWrapper(Image.decode(BitmapDrawable(Resources.getSystem(), bitmap), hardware = false)!!)
    }

    @Test
    fun `a fresh cell waits and shows no error text to tap`() {
        val fresh = view.obtainPage()

        assertEquals(PageCellState.Wait, fresh.state)
        assertFalse(fresh.isError)
        assertFalse(fresh.isLoaded)
    }

    @Test
    fun `a bound page waits, then shows progress, then its image`() {
        assertEquals(PageCellState.Wait, cell().state)

        adapter.onPagePercent(0, 0.5f)
        assertEquals(PageCellState.Progress(0.5f), cell().state)

        adapter.onPageSucceed(0, image())
        assertEquals(PageCellState.Loaded, cell().state)
        assertTrue(cell().isLoaded)
    }

    @Test
    fun `a failure shows its message as error text, a failure without one shows none`() {
        adapter.onPageFailed(0, "Failed to load")
        assertEquals(PageCellState.Error("Failed to load"), cell().state)
        assertTrue(cell().isError)

        adapter.onPageFailed(1, null)
        assertEquals(PageCellState.Error(null), cell(1).state)
        assertFalse(cell(1).isError)
    }

    @Test
    fun `a wait or percent for a page that shows its image keeps the image`() {
        adapter.onPageSucceed(0, image())

        adapter.onPageWait(0)
        adapter.onPagePercent(0, 0.2f)

        assertEquals(PageCellState.Loaded, cell().state)
    }

    /** The page menu's Refresh re-requests a shown page; when that fails the user must see it (and can retry). */
    @Test
    fun `a failed refresh of a shown page replaces the image with the error`() {
        adapter.onPageSucceed(0, image())

        adapter.onPageFailed(0, "Failed to load")

        assertEquals(PageCellState.Error("Failed to load"), cell().state)
        assertTrue(cell().isError)
    }

    @Test
    fun `a failed page can still load`() {
        adapter.onPageFailed(0, "Failed to load")
        adapter.onPageSucceed(0, image())

        assertEquals(PageCellState.Loaded, cell().state)
        assertFalse(cell().isError)
    }

    @Test
    fun `an unbound cell is reset to waiting`() {
        val page = cell()
        adapter.onPageSucceed(0, image())
        adapter.unbind(page)

        assertEquals(PageCellState.Wait, page.state)
    }

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 1600
    }
}
