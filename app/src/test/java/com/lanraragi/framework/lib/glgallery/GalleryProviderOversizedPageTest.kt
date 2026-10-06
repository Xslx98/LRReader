package com.lanraragi.framework.lib.glgallery

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import com.lanraragi.framework.lib.glview.image.ImageWrapper
import com.lanraragi.framework.lib.glview.view.GLRoot
import com.lanraragi.framework.lib.image.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Audit 2026-10-06 N3: a decoded page bigger than the whole reader cache
 * budget used to be put into the LruCache, evicted at once, released to zero
 * references and recycled before the GL thread could obtain it; the adapter
 * then re-requested the page forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class GalleryProviderOversizedPageTest {

    private class TestProvider(cacheMaxBytes: Int) : GalleryProvider(cacheMaxBytes) {
        val requested = mutableListOf<Int>()
        override fun size(): Int = 10
        override fun onRequest(index: Int) {
            requested += index
        }
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getError(): String? = null
    }

    private class RecordingListener : GalleryProvider.Listener {
        val succeeded = mutableListOf<Pair<Int, ImageWrapper>>()
        override fun onDataChanged() = Unit
        override fun onPageWait(index: Int) = Unit
        override fun onPagePercent(index: Int, percent: Float) = Unit
        override fun onPageSucceed(index: Int, image: ImageWrapper) {
            succeeded += index to image
        }
        override fun onPageFailed(index: Int, error: String?) = Unit
        override fun onDataChanged(index: Int) = Unit
    }

    /** GLRoot whose idle listeners run immediately on the calling thread. */
    private fun immediateGlRoot(): GLRoot =
        Proxy.newProxyInstance(GLRoot::class.java.classLoader, arrayOf(GLRoot::class.java)) { _, method, args ->
            if (method.name == "addOnGLIdleListener") {
                (args!![0] as GLRoot.OnGLIdleListener).onGLIdle(null, false)
            }
            null
        } as GLRoot

    @Before
    fun setUp() {
        Image.screenWidth = 1080
        Image.screenHeight = 1920
    }

    private fun image(side: Int): Image {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val image = Image.decode(BitmapDrawable(Resources.getSystem(), bitmap), hardware = false)
        assertNotNull(image)
        return image!!
    }

    @Test
    fun `page bigger than the cache budget is delivered alive and not cached`() {
        val pageBytes = 100 * 100 * 4
        val provider = TestProvider(cacheMaxBytes = pageBytes - 1)
        val listener = RecordingListener()
        provider.setListener(listener)
        provider.setGLRoot(immediateGlRoot())

        provider.notifyPageSucceed(3, image(100))

        assertEquals(1, listener.succeeded.size)
        val (index, wrapper) = listener.succeeded.single()
        assertEquals(3, index)
        // The GL thread can take its reference: the image was not recycled.
        assertTrue("oversized page must survive until shown", wrapper.obtain())
        assertTrue(wrapper.isUncached)
        assertFalse(provider.hasCache(3))
        // Showing then dropping the page recycles it (no other owner).
        wrapper.release()
        assertTrue(wrapper.isImageRecycled)
    }

    @Test
    fun `page within the budget stays cached`() {
        val provider = TestProvider(cacheMaxBytes = 100 * 100 * 4)
        val listener = RecordingListener()
        provider.setListener(listener)
        provider.setGLRoot(immediateGlRoot())

        provider.notifyPageSucceed(1, image(100))

        val wrapper = listener.succeeded.single().second
        assertFalse(wrapper.isUncached)
        assertTrue(provider.hasCache(1))
        provider.request(1)
        assertTrue("a cached page is served without a new decode", provider.requested.isEmpty())
    }

    @Test
    fun `oversized page dropped during teardown is released`() {
        val provider = TestProvider(cacheMaxBytes = 16)
        // No listener / GLRoot: the provider is being torn down.
        val page = image(100)
        provider.notifyPageSucceed(0, page)
        assertFalse(provider.hasCache(0))
        assertTrue("nobody owns the dropped page, so it must be recycled", page.isRecycled)
    }
}
