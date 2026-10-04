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
        }
        worker = LRRDownloadWorker(ApplicationProvider.getApplicationContext(), info, env)
        worker.listener = object : SpiderQueen.OnSpiderListener {
            override fun onGetPages(pages: Int) {}
            override fun onGet509(index: Int) {}
            override fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {}
            override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) {}
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

    private companion object {
        val ARCID = "a".repeat(40)
    }
}
