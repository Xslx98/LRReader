package com.lanraragi.reader.gallery

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.lib.image.Image
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.module.CoroutineModule
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Audit 2026-10-06 C18: the open helper's warmDir repeated the detail page's
 * decode of the start page (10-30 MB). warmDir now awaits a running warm for
 * the archive and skips the decode when the slot already holds the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPageCacheWarmDirSkipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val arcid = "c".repeat(40)
    private lateinit var context: Context
    private lateinit var dir: UniFile

    @Before
    fun setUp() {
        ServiceRegistry.initializeForTest(CoroutineModule())
        Image.screenWidth = 1080
        Image.screenHeight = 1920
        context = ApplicationProvider.getApplicationContext()
        val folder = tmp.newFolder("dl")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        FileOutputStream(File(folder, "0001.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        dir = UniFile.fromFile(folder)!!
    }

    @After
    fun tearDown() {
        runBlocking { ReaderPageCache.consumeDecodedPage(arcid, 0)?.recycle() }
    }

    private fun slotImage(): Image = Image.create(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))!!

    @Test(timeout = 15_000)
    fun `warmDir does not decode again when the slot already holds the page`() = runBlocking {
        val warm = slotImage()
        ReaderPageCache.storeDecodedSlotForTest(arcid, 0, warm)

        withTimeout(10_000) { ReaderPageCache.warmDir(context, arcid, dir, 0).join() }

        assertFalse("the slot's decode must not be replaced (and recycled)", warm.isRecycled)
        assertSame(warm, ReaderPageCache.consumeDecodedPage(arcid, 0))
        warm.recycle()
    }

    @Test(timeout = 15_000)
    fun `warmDir waits for the running warm of the archive, then reuses its slot`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val previous = CoroutineScope(Dispatchers.Default + Job()).launch { release.await() }
        ReaderPageCache.registerActiveWarmupForTest(arcid, previous)

        val second = ReaderPageCache.warmDir(context, arcid, dir, 0)
        assertNull(
            "warmDir must not run while the archive's previous warm is in flight",
            withTimeoutOrNull(1_000) { second.join() },
        )

        // The previous warm fills the slot, then finishes.
        val warm = slotImage()
        ReaderPageCache.storeDecodedSlotForTest(arcid, 0, warm)
        release.complete(Unit)
        withTimeout(10_000) { second.join() }

        assertFalse(warm.isRecycled)
        assertSame(warm, ReaderPageCache.consumeDecodedPage(arcid, 0))
        warm.recycle()
    }

    @Test(timeout = 15_000)
    fun `warmDir still decodes an empty slot`() = runBlocking {
        withTimeout(10_000) { ReaderPageCache.warmDir(context, arcid, dir, 0).join() }
        val decoded = ReaderPageCache.consumeDecodedPage(arcid, 0)
        assertNotNull(decoded)
        decoded!!.recycle()
    }
}
