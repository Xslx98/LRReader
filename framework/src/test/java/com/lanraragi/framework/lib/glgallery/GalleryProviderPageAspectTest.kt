package com.lanraragi.framework.lib.glgallery

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import com.lanraragi.framework.lib.image.Image
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [GalleryProvider.pageAspect]: what the spread planner knows about each page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class GalleryProviderPageAspectTest {

    private class TestProvider : GalleryProvider(cacheMaxBytes = 1 shl 20) {
        override fun size(): Int = 300
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getError(): String? = null

        fun header(index: Int, width: Int, height: Int) = recordPageBounds(index, width, height)

        fun remap() = forgetPageAspects()
    }

    private val provider = TestProvider()

    private fun image(width: Int, height: Int): Image {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return Image.decode(BitmapDrawable(Resources.getSystem(), bitmap), hardware = false)!!
    }

    @Test
    fun aDecodedPageIsKnown_othersAreUnknown() {
        provider.notifyPageSucceed(200, image(160, 100))

        assertEquals(1.6f, provider.pageAspect(200), 1e-4f)
        assertEquals(SpreadPlanner.UNKNOWN_ASPECT, provider.pageAspect(199), 0f)
        assertEquals(SpreadPlanner.UNKNOWN_ASPECT, provider.pageAspect(-1), 0f)
        assertEquals(SpreadPlanner.UNKNOWN_ASPECT, provider.pageAspect(5000), 0f)
    }

    @Test
    fun theDecodedSizeWinsOverTheHeader() {
        // A header read can disagree with the decode (EXIF rotation, say).
        provider.header(3, 140, 100)
        provider.notifyPageSucceed(3, image(70, 100))

        assertEquals(0.7f, provider.pageAspect(3), 1e-4f)
    }

    @Test
    fun aRemapForgetsEverything_andEmptySizesAreIgnored() {
        provider.header(1, 70, 100)
        provider.header(2, 0, 100)
        provider.remap()
        provider.header(4, 70, -1)

        for (p in 0..4) assertEquals("page $p", SpreadPlanner.UNKNOWN_ASPECT, provider.pageAspect(p), 0f)
    }
}
