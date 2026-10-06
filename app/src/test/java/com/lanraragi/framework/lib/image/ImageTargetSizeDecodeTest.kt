package com.lanraragi.framework.lib.image

import android.graphics.Bitmap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Measures actual decoded dimensions with and without a target-size hint.
 * Source is a 1000x1400 PNG — the shape of a near-full-res LANraragi
 * server thumbnail that previously decoded at sample size 1 against a
 * 1080x1920 screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageTargetSizeDecodeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        Image.screenWidth = 1080
        Image.screenHeight = 1920
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

    @Test
    fun `decode without hint keeps screen-based sampling (reader path)`() {
        val image = FileInputStream(pngFile(1000, 1400)).use {
            Image.decode(it, false)
        }
        assertNotNull(image)
        // min(1000/1080, 1400/1920) = 0 -> coerced to sample 1 -> full size
        assertEquals(1000, image!!.width)
        assertEquals(1400, image.height)
        image.recycle()
    }

    /**
     * Audit 2026-10-06c PERF-01: a 15 MiB file whose pixels fit the screen must
     * decode at full resolution (the old fileSize / 10 MiB + 1 floor gave sample 2).
     * The PNG is padded after IEND so the file is large but the pixels are not.
     */
    @Test
    fun `large file with screen-fit pixels decodes at full resolution`() {
        val file = pngFile(1000, 1400)
        java.io.RandomAccessFile(file, "rw").use { it.setLength(15L * 1024 * 1024) }
        val image = FileInputStream(file).use { Image.decode(it, false) }
        assertNotNull(image)
        assertEquals(1000, image!!.width)
        assertEquals(1400, image.height)
        image.recycle()
    }

    @Test
    fun `large file with target hint samples by pixels only`() {
        val file = pngFile(1000, 1400)
        java.io.RandomAccessFile(file, "rw").use { it.setLength(25L * 1024 * 1024) }
        val image = FileInputStream(file).use { Image.decode(it, false, 336, 470) }
        assertNotNull(image)
        // pixel fit = 2; the old file-size floor would have forced 3
        assertEquals(500, image!!.width)
        assertEquals(700, image.height)
        image.recycle()
    }

    @Test
    fun `decode with target hint samples down to cell scale`() {
        val image = FileInputStream(pngFile(1000, 1400)).use {
            Image.decode(it, false, 336, 470)
        }
        assertNotNull(image)
        // sample = min(1000/336, 1400/470) = 2 -> 500x700
        assertEquals(500, image!!.width)
        assertEquals(700, image.height)
        image.recycle()
    }

    @Test
    fun `decode with target hint never upscales small sources`() {
        val image = FileInputStream(pngFile(200, 280)).use {
            Image.decode(it, false, 336, 470)
        }
        assertNotNull(image)
        assertEquals(200, image!!.width)
        assertEquals(280, image.height)
        image.recycle()
    }
}
