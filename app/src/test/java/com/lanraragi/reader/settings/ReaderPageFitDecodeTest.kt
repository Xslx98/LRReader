package com.lanraragi.reader.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.framework.lib.image.Image
import com.lanraragi.framework.lib.image.PageFit
import com.lanraragi.reader.Settings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * The reader's scale mode reaches the page decode (owner decision 2026-10-06):
 * ReadingSettings installs [Image.pageFitSource], and the no-target
 * [Image.decode] the providers call samples by it. Screen and pages are a
 * quarter of real sizes (270x600 for a 1080x2400 phone) to keep the PNGs small.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPageFitDecodeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        Image.screenWidth = 270
        Image.screenHeight = 600
    }

    @After
    fun tearDown() {
        ReadingSettings.putReadingDirection(GalleryView.LAYOUT_RIGHT_TO_LEFT)
        ReadingSettings.putPageScaling(GalleryView.SCALE_FIT)
        Image.pageFitSource = { PageFit.FIT }
    }

    private fun pngFile(width: Int, height: Int): File {
        val file = tmp.newFile("page_${width}x$height.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun readerDecode(file: File): Image {
        val image = FileInputStream(file).use { Image.decode(it, false) }
        assertNotNull(image)
        return image!!
    }

    @Test
    fun `pager fit decodes a scan at the fit size`() {
        ReadingSettings.putReadingDirection(GalleryView.LAYOUT_RIGHT_TO_LEFT)
        ReadingSettings.putPageScaling(GalleryView.SCALE_FIT)
        val image = readerDecode(pngFile(700, 1000))
        // max ratio 2 (the old min ratio kept 700x1000)
        assertEquals(350, image.width)
        assertEquals(500, image.height)
        image.recycle()
    }

    @Test
    fun `top-to-bottom keeps the page width whatever the scale mode`() {
        ReadingSettings.putReadingDirection(GalleryView.LAYOUT_TOP_TO_BOTTOM)
        ReadingSettings.putPageScaling(GalleryView.SCALE_FIT)
        val image = readerDecode(pngFile(700, 1000))
        // width 700 / longer screen side 600 = 1
        assertEquals(700, image.width)
        assertEquals(1000, image.height)
        image.recycle()
    }

    @Test
    fun `wide page follows fit and fit height`() {
        val file = pngFile(3600, 800)
        ReadingSettings.putReadingDirection(GalleryView.LAYOUT_LEFT_TO_RIGHT)
        ReadingSettings.putPageScaling(GalleryView.SCALE_FIT)
        val fit = readerDecode(file)
        // FIT: min(max(13, 1), max(6, 2)) = 6 -> 600 wide, the landscape fit
        assertEquals(600, fit.width)
        assertTrue("height ${fit.height}", fit.height in 133..134)
        fit.recycle()

        ReadingSettings.putPageScaling(GalleryView.SCALE_FIT_HEIGHT)
        val fitHeight = readerDecode(file)
        // FIT_HEIGHT: 800 / 600 = 1 -> full size, shown 600 px tall
        assertEquals(3600, fitHeight.width)
        assertEquals(800, fitHeight.height)
        fitHeight.recycle()
    }

    @Test
    fun `the reason-reporting decode follows the scale mode too`() {
        // Image.decodeResult is what the online and tank providers call (06d
        // PERF-01); it must sample like Image.decode, or webtoon strips are
        // decoded at the FIT sample and look blurry.
        ReadingSettings.putReadingDirection(GalleryView.LAYOUT_TOP_TO_BOTTOM)
        ReadingSettings.putPageScaling(GalleryView.SCALE_FIT)
        val result = FileInputStream(pngFile(700, 1000)).use { Image.decodeResult(it, false) }
        val image = (result as com.lanraragi.framework.lib.image.DecodeResult.Ok).value
        assertEquals(700, image.width)
        assertEquals(1000, image.height)
        image.recycle()
    }

    @Test
    fun `decode without a target through the thumbnail overload ignores the scale mode`() {
        ReadingSettings.putReadingDirection(GalleryView.LAYOUT_RIGHT_TO_LEFT)
        ReadingSettings.putPageScaling(GalleryView.SCALE_FIT)
        val image = FileInputStream(pngFile(700, 1000)).use { Image.decode(it, false, 0, 0) }
        assertNotNull(image)
        // old rule: min(700 / 270, 1000 / 600) = 1
        assertEquals(700, image!!.width)
        image.recycle()
    }
}
