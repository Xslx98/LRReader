package com.hippo.ehviewer.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.ServiceRegistry
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests for [LrrTankMemberSource]: file-list coalescing, page download +
 * decode through the shared standalone-reader cache dir, cache hits that
 * skip the network, and the quiet-cancel marker.
 *
 * NATIVE graphics mode: Image.decode routes through ImageDecoder, whose
 * legacy Robolectric shadow crashes on mmap'd buffers (see memory
 * `avd-mock-lrr-thumbnail-smoke`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LrrTankMemberSourceTest {

    private lateinit var ctx: Context
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    /** Real PNG bytes with enough entropy to clear MIN_IMAGE_SIZE (1KB). */
    private val pngBytes: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val rnd = Random(42)
        for (x in 0 until WIDTH) {
            for (y in 0 until HEIGHT) {
                bitmap.setPixel(x, y, Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray().also { check(it.size > 1024) { "test png too small: ${it.size}" } }
    }

    private val fileListRequests = java.util.concurrent.atomic.AtomicInteger(0)
    private val pageRequests = java.util.concurrent.atomic.AtomicInteger(0)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ServiceRegistry.initializeForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("/files") -> {
                        fileListRequests.incrementAndGet()
                        val base = "/api/archives/$ARCID/page?path=mock"
                        MockResponse()
                            .addHeader("Content-Type", "application/json")
                            .setBody(
                                """{"job":-1,"pages":["$base/001.png","$base/002.png","$base/003.png"]}"""
                            )
                    }
                    "page?path=" in path -> {
                        pageRequests.incrementAndGet()
                        MockResponse()
                            .addHeader("Content-Type", "image/png")
                            .setBody(Buffer().write(pngBytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
        // The source writes into the member's standalone reader cache dir;
        // wipe it so runs stay independent.
        ReaderPageCache.ensureCacheDir(ctx, ARCID).deleteRecursively()
    }

    private fun newSource() = LrrTankMemberSource(
        ctx, ARCID,
        serverUrl = server.url("").toString().removeSuffix("/"),
        pageClient = client,
        listClient = client,
    )

    @Test
    fun `ensurePageCount fetches the list once and reports the real count`() = runBlocking {
        val source = newSource()
        assertEquals(3, source.ensurePageCount())
        assertEquals(3, source.ensurePageCount())
        assertEquals(3, source.knownPageCount())
        assertEquals("second call must not refetch", 1, fileListRequests.get())
    }

    @Test
    fun `obtainImage downloads, decodes, and later hits the shared cache`() = runBlocking {
        val source = newSource()
        source.ensurePageCount()

        val image = source.obtainImage(0)
        assertNotNull(image)
        assertEquals(WIDTH, image!!.width)
        assertEquals(HEIGHT, image.height)
        image.recycle()
        assertEquals(1, pageRequests.get())

        // Same page again: served from the on-disk cache, no new request.
        source.obtainImage(0)?.recycle()
        assertEquals("cache hit must not re-download", 1, pageRequests.get())
    }

    @Test
    fun `obtainImage out of bounds throws`(): Unit = runBlocking {
        val source = newSource()
        source.ensurePageCount()
        assertThrows(IOException::class.java) {
            runBlocking { source.obtainImage(7) }
        }
    }

    @Test
    fun `cancelAll turns later fetches into the quiet cancel marker`(): Unit = runBlocking {
        val source = newSource()
        source.ensurePageCount()
        source.cancelAll()
        val e = assertThrows(IOException::class.java) {
            runBlocking { source.obtainImage(1) }
        }
        assertTrue("expected quiet-cancel marker, got $e", e is TankPageCancelledException)
    }

    private companion object {
        val ARCID = "e".repeat(40)
        const val WIDTH = 64
        const val HEIGHT = 48
    }
}
