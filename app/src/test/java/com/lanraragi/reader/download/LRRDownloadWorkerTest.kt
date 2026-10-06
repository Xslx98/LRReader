package com.lanraragi.reader.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.lib.image.Image
import com.lanraragi.reader.Settings
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.spider.SpiderQueen
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end tests of [LRRDownloadWorker] against a MockWebServer (audit
 * 2026-10-04 C09 + C21): page list → pages on disk, and the page retry
 * policy — permanent failures abort the archive without retries, transient
 * ones are retried, corrupt bodies get the bounded retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LRRDownloadWorkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var worker: LRRDownloadWorker

    /** Requests per page index. */
    private val pageHits = ConcurrentHashMap<Int, AtomicInteger>()

    /** Answer for page n on its k-th request (1-based). */
    private var pageAnswer: (page: Int, hit: Int) -> MockResponse = { _, _ -> image() }

    private var pageCount = 3

    private var usable = Long.MAX_VALUE

    /** Called on every reported page success (0-based index). */
    private var onSuccess: (index: Int) -> Unit = {}

    /** Answer for the page-list request on its k-th hit (1-based); null = the list. */
    private var filesAnswer: (hit: Int) -> MockResponse? = { null }

    /** Arrival time (ms) of each page-list request. */
    private val filesHitTimes = java.util.Collections.synchronizedList(mutableListOf<Long>())

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        Settings.initialize(context)
        dir = tmp.newFolder("Vol 1")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (request.method == "HEAD") return MockResponse()
                if (path.endsWith("/files")) {
                    filesHitTimes += System.currentTimeMillis()
                    filesAnswer(filesHitTimes.size)?.let { return it }
                    val pages = (1..pageCount).joinToString(",") { "\"./page?path=$it.jpg\"" }
                    return MockResponse().setBody("""{"pages":[$pages]}""")
                }
                val page = Regex("path=(\\d+)\\.jpg").find(path)?.groupValues?.get(1)?.toInt()
                    ?: return MockResponse().setResponseCode(500)
                val hit = pageHits.getOrPut(page) { AtomicInteger() }.incrementAndGet()
                return pageAnswer(page, hit)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        if (::worker.isInitialized) worker.cancel()
        server.shutdown()
    }

    private fun image(): MockResponse {
        val bytes = ByteArray(2048).also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte()
        }
        return MockResponse().setBody(Buffer().write(bytes))
    }

    private class Result {
        val done = CountDownLatch(1)
        @Volatile var finished = -1
        @Volatile var total = -1
    }

    private fun run(): Result {
        val result = Result()
        val client = OkHttpClient()
        val baseUrl = server.url("/").toString()
        val info = DownloadInfo().apply {
            arcid = ARCID
            title = "Vol 1"
        }
        val env = object : LRRDownloadWorker.Env {
            override suspend fun serverUrl(info: DownloadInfo) = baseUrl
            override suspend fun downloadDir(info: DownloadInfo) = dir
            override fun isNetworkAvailable() = true
            override val listClient = client
            override val pageClient = client
            override fun usableBytes(dir: File) = usable
        }
        worker = LRRDownloadWorker(ApplicationProvider.getApplicationContext(), info, env)
        worker.listener = object : SpiderQueen.OnSpiderListener {
            override fun onGetPages(pages: Int) {}
            override fun onGet509(index: Int) {}
            override fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {}
            override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) = onSuccess(index)
            override fun onPageFailure(index: Int, error: String, finished: Int, downloaded: Int, total: Int) {}
            override fun onFinish(finished: Int, downloaded: Int, total: Int) {
                result.finished = finished
                result.total = total
                result.done.countDown()
            }
            override fun onGetImageSuccess(index: Int, image: Image) {}
            override fun onGetImageFailure(index: Int, error: String) {}
        }
        worker.start()
        assertTrue("worker never finished", result.done.await(30, TimeUnit.SECONDS))
        return result
    }

    private fun hits(page: Int) = pageHits[page]?.get() ?: 0

    @Test
    fun allPagesLandOnDiskWithMarker() {
        val r = run()

        assertEquals(3, r.finished)
        assertEquals(3, r.total)
        assertEquals(3, dir.listFiles()!!.count { it.name.endsWith(".jpg") })
        assertEquals(ARCID, DownloadDirMarker.read(com.lanraragi.framework.unifile.UniFile.fromFile(dir)!!)?.arcid)
        assertEquals(null, worker.failureReason)
    }

    @Test
    fun unauthorized_abortsArchiveWithoutRetrying() {
        pageCount = 40
        pageAnswer = { _, _ -> MockResponse().setResponseCode(401) }

        val r = run()

        assertEquals(0, r.finished)
        assertEquals(DownloadFailureReason.AUTH, worker.failureReason)
        // No page is asked twice, and the window stops claiming new pages.
        assertTrue(pageHits.values.all { it.get() == 1 })
        assertTrue("pages requested: ${pageHits.size}", pageHits.size < pageCount)
    }

    @Test
    fun serverError_isRetriedHonouringRetryAfter() {
        pageAnswer = { page, hit ->
            if (page == 2 && hit == 1) {
                // 502, not 503: OkHttp itself retries a 503 that carries Retry-After: 0.
                MockResponse().setResponseCode(502).setHeader("Retry-After", "0")
            } else {
                image()
            }
        }

        val r = run()

        assertEquals(3, r.finished)
        assertEquals(2, hits(2))
        assertEquals(null, worker.failureReason)
    }

    @Test
    fun corruptBody_failsThatPageAfterTwoAttempts() {
        pageAnswer = { page, _ ->
            if (page == 3) MockResponse().setBody("<html>not an image</html>") else image()
        }

        val r = run()

        assertEquals(2, r.finished)
        assertEquals(2, hits(3))
        assertEquals(DownloadFailureReason.CORRUPT, worker.failureReason)
    }

    @Test
    fun lowFreeSpace_abortsBeforeAnyPageRequest() {
        usable = LRRDownloadWorker.MIN_FREE_BYTES - 1

        val r = run()

        assertEquals(0, r.finished)
        assertEquals(DownloadFailureReason.NO_SPACE, worker.failureReason)
        assertEquals(0, pageHits.size)
    }

    @Test
    fun lowFreeSpace_stillFinishesAnArchiveAlreadyOnDisk() {
        run()
        pageHits.clear()
        usable = 0

        val r = run()

        assertEquals(3, r.finished)
        assertEquals(null, worker.failureReason)
        assertEquals(0, pageHits.size)
    }

    @Test
    fun pageListNotFound_failsWithNotFoundReason() {
        filesAnswer = { MockResponse().setResponseCode(404).setBody("""{"success":0,"error":"gone"}""") }

        val r = run()

        assertEquals(0, r.total)
        assertEquals(DownloadFailureReason.NOT_FOUND, worker.failureReason)
        assertEquals(0, pageHits.size)
        // Permanent: asked once, no retry (audit 2026-10-06e REL-02).
        assertEquals(1, filesHitTimes.size)
    }

    @Test(timeout = 60_000)
    fun pageListServerError_isRetriedThenTheArchiveDownloads() {
        // A bare 503 (no Retry-After): OkHttp does not retry it itself.
        filesAnswer = { hit -> if (hit == 1) MockResponse().setResponseCode(503) else null }

        val r = run()

        assertEquals(3, r.finished)
        assertEquals(3, r.total)
        assertEquals(2, filesHitTimes.size)
        assertEquals(null, worker.failureReason)
    }

    @Test(timeout = 60_000)
    fun pageListTooManyRequests_waitsForRetryAfter() {
        filesAnswer = { hit ->
            if (hit == 1) MockResponse().setResponseCode(429).setHeader("Retry-After", "2") else null
        }

        val r = run()

        assertEquals(3, r.finished)
        assertEquals(2, filesHitTimes.size)
        // The 1 s back-off (±20 %) would retry well before the 2 s asked for.
        val waited = filesHitTimes[1] - filesHitTimes[0]
        assertTrue("retried after $waited ms", waited >= 1_900)
    }

    @Test(timeout = 60_000)
    fun pageListServerError_failsOnceTheRetryBudgetIsSpent() {
        // 502 + Retry-After: 0 keeps the test fast; OkHttp leaves a 502 alone.
        filesAnswer = { MockResponse().setResponseCode(502).setHeader("Retry-After", "0") }

        val r = run()

        assertEquals(0, r.total)
        assertEquals(PageRetryPolicy.TRANSIENT_ATTEMPTS, filesHitTimes.size)
        assertEquals(DownloadFailureReason.SERVER, worker.failureReason)
        assertEquals(0, pageHits.size)
    }

    @Test(timeout = 60_000)
    fun pageListMalformed_failsAtOnce() {
        filesAnswer = { MockResponse().setBody("""{"nope":1}""") }

        val r = run()

        assertEquals(0, r.total)
        assertEquals(1, filesHitTimes.size)
        assertEquals(DownloadFailureReason.UNKNOWN, worker.failureReason)
    }

    /** On-disk file of 1-based page [n] (the worker's naming). */
    private fun pageFile(n: Int) = File(dir, "%04d.jpg".format(n))

    /** Audit 2026-10-06e P4-d: a page a reader marked as damaged is fetched again. */
    @Test(timeout = 60_000)
    fun markedPage_isFetchedAgainAndUnmarked() {
        run()
        pageHits.clear()
        assertTrue(DownloadPageRepair.mark(pageFile(2)))
        val hitsWhenCounted = AtomicInteger(-1)
        onSuccess = { index -> if (index == 1) hitsWhenCounted.set(hits(2)) }

        val r = run()

        assertEquals(3, r.finished)
        assertEquals("only the marked page is fetched again", mapOf(2 to 1), pageHits.mapValues { it.value.get() })
        assertEquals("counted only once it was fetched again", 1, hitsWhenCounted.get())
        assertFalse(DownloadPageRepair.isMarked(pageFile(2)))
        assertEquals(null, worker.failureReason)
    }

    @Test(timeout = 60_000)
    fun pageMarkedAfterTheWindowSkippedIt_isRepairedBeforeTheFinish() {
        run()
        pageHits.clear()
        // The reader marks page 1 right after the window counted it as on disk.
        onSuccess = { index -> if (index == 0) DownloadPageRepair.mark(pageFile(1)) }

        val r = run()

        assertEquals(3, r.finished)
        assertEquals(1, hits(1))
        assertFalse(DownloadPageRepair.isMarked(pageFile(1)))
    }

    @Test(timeout = 60_000)
    fun markedPageWhoseRepairFails_noLongerCounts() {
        run()
        pageHits.clear()
        onSuccess = { index -> if (index == 0) DownloadPageRepair.mark(pageFile(1)) }
        pageAnswer = { _, _ -> MockResponse().setResponseCode(502).setHeader("Retry-After", "0") }

        val r = run()

        assertEquals(2, r.finished)
        assertEquals(PageRetryPolicy.TRANSIENT_ATTEMPTS, hits(1))
        assertEquals(DownloadFailureReason.SERVER, worker.failureReason)
    }

    private companion object {
        val ARCID = "a".repeat(40)
    }
}
