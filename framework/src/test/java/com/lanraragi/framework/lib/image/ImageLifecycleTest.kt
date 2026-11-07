package com.lanraragi.framework.lib.image

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.content.res.Resources
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ImageLifecycleTest {

    @Before
    fun setUp() {
        Image.screenWidth = 1080
        Image.screenHeight = 1920
    }

    private fun createTestImage(): Image? {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val drawable = BitmapDrawable(Resources.getSystem(), bitmap)
        return Image.decode(drawable, hardware = false)
    }

    @Test
    fun `decode with drawable creates valid Image`() {
        val image = createTestImage()
        assertNotNull(image)
        image!!
        assertEquals(100, image.width)
        assertEquals(100, image.height)
        assertFalse(image.animated)
        assertFalse(image.isRecycled)
    }

    @Test
    fun `decode with null drawable returns null`() {
        val image = Image.decode(null as android.graphics.drawable.Drawable?)
        assertNull(image)
    }

    @Test
    fun `recycle sets isRecycled to true`() {
        val image = createTestImage()!!
        assertFalse(image.isRecycled)
        image.recycle()
        assertTrue(image.isRecycled)
    }

    @Test
    fun `double recycle is safe`() {
        val image = createTestImage()!!
        image.recycle()
        // Second recycle should not throw
        image.recycle()
        assertTrue(image.isRecycled)
    }

    @Test
    fun `obtain returns true for live image`() {
        val image = createTestImage()!!
        assertTrue(image.obtain())
        // Clean up: release twice (once for our obtain, once for final)
        image.release()
        image.recycle()
    }

    @Test
    fun `obtain returns false after recycle`() {
        val image = createTestImage()!!
        image.recycle()
        assertFalse(image.obtain())
    }

    @Test
    fun `release triggers recycle when references reach zero`() {
        val image = createTestImage()!!
        assertTrue(image.obtain())
        assertFalse(image.isRecycled)
        // release decrements mReferences to 0, triggering recycle
        image.release()
        assertTrue(image.isRecycled)
    }

    @Test
    fun `getDrawable returns valid drawable`() {
        val image = createTestImage()!!
        val drawable = image.getDrawable()
        assertNotNull(drawable)
        assertTrue(drawable is BitmapDrawable)
        // getDrawable calls obtain internally, so we need to release
        image.release()
        image.recycle()
    }

    @Test(expected = IllegalStateException::class)
    fun `getDrawable throws after recycle`() {
        val image = createTestImage()!!
        image.recycle()
        image.getDrawable()
    }

    @Test
    fun `isOpaque returns false after recycle`() {
        val image = createTestImage()!!
        // After recycle, mDrawableRef.get() is null, so opacity check returns false
        image.recycle()
        assertFalse(image.isOpaque)
    }

    @Test
    fun `create with bitmap returns valid Image`() {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val image = Image.create(bitmap)
        assertNotNull(image)
        image!!
        assertEquals(50, image.width)
        assertEquals(50, image.height)
        assertFalse(image.isRecycled)
        image.recycle()
    }
}
