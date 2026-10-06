package com.lanraragi.reader.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.download.DownloadPageRepair
import com.lanraragi.reader.download.DurablePageWrite
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Page bytes the server returns instead of [pngBytes] when set. */
    @Volatile
    private var pageBody: ByteArray? = null

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
                            .setBody(Buffer().write(pageBody ?: pngBytes))
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

    private fun newSource(store: HybridPageStore? = null) = LrrTankMemberSource(
        ctx, ARCID,
        serverUrl = server.url("").toString().removeSuffix("/"),
        pageClient = client,
        listClient = client,
        store = store,
    )

    private fun newStore(
        durable: DurablePageWrite = DurablePageWrite(usableBytes = { Long.MAX_VALUE }, syncFile = {}),
    ): Pair<HybridPageStore, java.io.File> {
        val downloadDir = java.io.File(ctx.cacheDir, "tank_member_dl_$ARCID").also { it.deleteRecursively() }
        return HybridPageStore(downloadDir, ReaderPageCache.ensureCacheDir(ctx, ARCID), durable) to downloadDir
    }

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

    @Test
    fun `hybrid store writes fetched pages into the download dir under worker naming`(): Unit = runBlocking {
        val (store, downloadDir) = newStore()
        val source = newSource(store)
        source.ensurePageCount()

        source.obtainImage(0)?.recycle()

        assertEquals(1, pageRequests.get())
        assertTrue(java.io.File(downloadDir, "0001.png").length() > ReaderPageCache.MIN_IMAGE_SIZE)
        assertTrue(java.io.File(downloadDir, ".nomedia").isFile)
        assertFalse(java.io.File(ReaderPageCache.ensureCacheDir(ctx, ARCID), "page_0").exists())
        downloadDir.deleteRecursively()
    }

    @Test
    fun `hybrid store serves a worker-landed page without touching the network`(): Unit = runBlocking {
        val (store, downloadDir) = newStore()
        downloadDir.mkdirs()
        java.io.File(downloadDir, "0002.png").writeBytes(pngBytes)
        val source = newSource(store)
        source.ensurePageCount()

        val image = source.obtainImage(1)
        assertNotNull(image)
        image!!.recycle()
        assertEquals("page on disk must not be re-fetched", 0, pageRequests.get())
        downloadDir.deleteRecursively()
    }

    @Test
    fun `hybrid store adopts a warm reader-cache page instead of fetching`(): Unit = runBlocking {
        val (store, downloadDir) = newStore()
        val warm = java.io.File(ReaderPageCache.ensureCacheDir(ctx, ARCID), "page_2")
        warm.writeBytes(pngBytes)
        val source = newSource(store)
        source.ensurePageCount()

        source.obtainImage(2)?.recycle()

        assertEquals(0, pageRequests.get())
        assertTrue(java.io.File(downloadDir, "0003.png").length() > ReaderPageCache.MIN_IMAGE_SIZE)
        assertFalse("warm copy is moved, not duplicated", warm.exists())
        downloadDir.deleteRecursively()
    }

    /** Audit REL-04: a member page fetched into the download dir is fsynced; a below-floor volume blocks it. */
    @Test
    fun `hybrid store writes download-dir pages durably and respects the free-space floor`(): Unit = runBlocking {
        val syncs = java.util.concurrent.atomic.AtomicInteger(0)
        var usable = Long.MAX_VALUE
        val durable = DurablePageWrite(usableBytes = { usable }, syncFile = { syncs.incrementAndGet() })
        val (store, downloadDir) = newStore(durable)
        val source = newSource(store)
        source.ensurePageCount()

        source.obtainImage(0)?.recycle()
        assertEquals("download-dir page must be fsynced", 1, syncs.get())

        usable = DurablePageWrite.MIN_FREE_BYTES - 1
        assertThrows(IOException::class.java) {
            runBlocking { source.obtainImage(1) }
        }
        assertEquals("no page request below the floor", 1, pageRequests.get())
        assertFalse(java.io.File(downloadDir, "0002.png").exists())
        downloadDir.deleteRecursively()
    }

    private fun decodeFailure(source: LrrTankMemberSource, page0: Int): PageFailure {
        val e = assertThrows(PageDecodeException::class.java) {
            runBlocking { source.obtainImage(page0) }
        }
        return e.failure
    }

    /** Audit 2026-10-06d PERF-01: a valid page the device cannot decode is kept, not re-fetched. */
    @Test
    fun `unsupported format fails with its reason and is neither deleted nor re-fetched`(): Unit = runBlocking {
        pageBody = PageFixtures.avif // Robolectric runs API 30: no AVIF decoder
        val source = newSource()
        source.ensurePageCount()
        val cached = java.io.File(ReaderPageCache.ensureCacheDir(ctx, ARCID), "page_0")

        val failure = decodeFailure(source, 0)
        assertEquals(PageFailure.Unsupported(PageImageFormat.AVIF), failure)
        assertEquals("This image format (AVIF) isn't supported on this device", failure.message(ctx))
        assertTrue("valid page must stay cached", cached.exists())

        assertEquals(PageFailure.Unsupported(PageImageFormat.AVIF), decodeFailure(source, 0))
        assertEquals("no re-download of a valid page", 1, pageRequests.get())
    }

    @Test
    fun `damaged cache page is dropped so the next request re-fetches it`(): Unit = runBlocking {
        pageBody = PageFixtures.damagedPng
        val source = newSource()
        source.ensurePageCount()
        val cached = java.io.File(ReaderPageCache.ensureCacheDir(ctx, ARCID), "page_0")

        assertEquals(PageFailure.Corrupt, decodeFailure(source, 0))
        assertFalse(cached.exists())

        pageBody = null
        source.obtainImage(0)!!.recycle()
        assertEquals(2, pageRequests.get())
    }

    @Test
    fun `damaged download-dir page is never deleted on a decode failure`(): Unit = runBlocking {
        pageBody = PageFixtures.damagedPng
        val (store, downloadDir) = newStore()
        val source = newSource(store)
        source.ensurePageCount()
        val page = java.io.File(downloadDir, "0001.png")

        // The server keeps sending damaged bytes: the fresh reader-cache copy
        // fails too, and only that copy is dropped.
        assertEquals(PageFailure.Corrupt, decodeFailure(source, 0))
        assertArrayEquals("the download worker owns this file", PageFixtures.damagedPng, page.readBytes())
        assertEquals(2, pageRequests.get())
        downloadDir.deleteRecursively()
    }

    /** Audit 2026-10-06e P4-d: hand the damaged page to the download side, show a fresh copy now. */
    @Test
    fun `damaged download-dir page is handed over and read fresh from the reader cache`(): Unit = runBlocking {
        val (store, downloadDir) = newStore()
        downloadDir.mkdirs()
        val page = java.io.File(downloadDir, "0001.png").apply { writeBytes(PageFixtures.damagedPng) }
        val source = newSource(store)
        source.ensurePageCount()

        val image = source.obtainImage(0)

        assertNotNull(image)
        image!!.recycle()
        assertEquals("one fetch, into the reader cache", 1, pageRequests.get())
        assertArrayEquals("only the download pipeline replaces it", PageFixtures.damagedPng, page.readBytes())
        assertTrue("marked for the worker", DownloadPageRepair.isMarked(page))
        assertTrue(java.io.File(ReaderPageCache.ensureCacheDir(ctx, ARCID), "page_0").exists())
        downloadDir.deleteRecursively()
    }

    private companion object {
        val ARCID = "e".repeat(40)
        const val WIDTH = 64
        const val HEIGHT = 48
    }
}
