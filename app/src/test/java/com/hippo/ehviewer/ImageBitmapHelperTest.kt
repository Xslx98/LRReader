package com.hippo.ehviewer

import android.graphics.Bitmap
import com.hippo.lib.image.Image
import com.hippo.streampipe.InputStreamPipe
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageBitmapHelperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val helper = ImageBitmapHelper()

    @Before
    fun setUp() {
        Image.screenWidth = 1080
        Image.screenHeight = 1920
    }

    private class FilePipe(private val file: File) : InputStreamPipe {
        override fun obtain() = Unit
        override fun release() = Unit
        override fun open(): InputStream = FileInputStream(file)
        override fun close() = Unit
    }

    private fun pngFile(width: Int, height: Int): File {
        val file = tmp.newFile("src_${width}x$height.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun imageOf(width: Int, height: Int): Image {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return Image.create(bitmap)!!
    }

    @Test
    fun `four-arg decode threads target size into the image decode`() {
        val image = helper.decode(FilePipe(pngFile(1000, 1400)), false, 336, 470)
        assertNotNull(image)
        assertEquals(500, image!!.width)
        assertEquals(700, image.height)
        image.recycle()
    }

    @Test
    fun `two-arg decode keeps legacy screen-based sampling`() {
        val image = helper.decode(FilePipe(pngFile(1000, 1400)), false)
        assertNotNull(image)
        assertEquals(1000, image!!.width)
        assertEquals(1400, image.height)
        image.recycle()
    }

    @Test
    fun `sampled portrait thumbnail is admitted to memory cache`() {
        // 500x707 is what a ~1000px LRR thumb decodes to under a detail-header
        // floor target; it must be memory-cacheable for scroll hit rate.
        val image = imageOf(500, 707)
        assertTrue(helper.useMemoryCache("key", image))
        image.recycle()
    }

    @Test
    fun `near-full-res decode stays out of memory cache`() {
        val image = imageOf(1000, 1414)
        assertFalse(helper.useMemoryCache("key", image))
        image.recycle()
    }

    @Test
    fun `cap follows the injected decode floor on high-density devices`() {
        // 560 dpi: the 128x192dp decode floor is 448x672 px. A 700x990 server
        // thumb decodes at sample=1 (693k px) — above the legacy fixed
        // 512*768 cap, so such devices silently re-decoded every rebind from
        // disk. With a floor-derived cap it must be admitted.
        val dense = ImageBitmapHelper(ImageBitmapHelper.cacheCapForFloor(448, 672))
        val image = imageOf(700, 990)
        assertTrue(dense.useMemoryCache("key", image))
        image.recycle()
    }

    @Test
    fun `floor-derived cap covers integer sample-size headroom`() {
        // Integer sampling can leave a decode just under 2x the floor per
        // dimension; the cap must admit that worst case.
        val cap = ImageBitmapHelper.cacheCapForFloor(448, 672)
        val dense = ImageBitmapHelper(cap)
        val worstCase = imageOf(448 * 2 - 1, 672 * 2 - 1)
        assertTrue(dense.useMemoryCache("key", worstCase))
        worstCase.recycle()
    }

    @Test
    fun `floor-derived cap still rejects unbounded decodes`() {
        val dense = ImageBitmapHelper(ImageBitmapHelper.cacheCapForFloor(448, 672))
        val huge = imageOf(2000, 2800)
        assertFalse(dense.useMemoryCache("key", huge))
        huge.recycle()
    }
}
