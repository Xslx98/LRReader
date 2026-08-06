package com.hippo.ehviewer.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.unifile.UniFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests for [DirTankMemberSource]: enumeration through the worker's
 * numeric naming, decode, and out-of-range behavior. NATIVE graphics for
 * real ImageDecoder decode (see LrrTankMemberSourceTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirTankMemberSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ServiceRegistry.initializeForTest()
    }

    private fun pngInto(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rnd = Random(7)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
            }
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun workerNamedDir(pages: Int): File {
        val dir = tmp.newFolder("member")
        for (p in 1..pages) {
            pngInto(File(dir, "%04d.png".format(p)), 32 + p, 24)
        }
        return dir
    }

    private fun sourceFor(dir: File) = DirTankMemberSource(
        ctx, "f".repeat(40), UniFile.fromFile(dir)!!
    )

    @Test
    fun `ensurePageCount enumerates worker-named pages`() = runBlocking {
        val source = sourceFor(workerNamedDir(3))
        assertEquals(3, source.ensurePageCount())
        assertEquals(3, source.knownPageCount())
    }

    @Test
    fun `obtainImage decodes the page for its real page number`() = runBlocking {
        val source = sourceFor(workerNamedDir(3))
        source.ensurePageCount()
        // page0=1 is "0002.png", width 32+2
        val image = source.obtainImage(1)
        assertNotNull(image)
        assertEquals(34, image!!.width)
        image.recycle()
    }

    @Test
    fun `obtainImage out of range throws`(): Unit = runBlocking {
        val source = sourceFor(workerNamedDir(2))
        source.ensurePageCount()
        assertThrows(IOException::class.java) {
            runBlocking { source.obtainImage(5) }
        }
    }

    @Test
    fun `ensurePageCount on a vanished dir throws`(): Unit = runBlocking {
        val dir = workerNamedDir(1)
        dir.deleteRecursively()
        assertThrows(IOException::class.java) {
            runBlocking { sourceFor(dir).ensurePageCount() }
        }
    }
}
