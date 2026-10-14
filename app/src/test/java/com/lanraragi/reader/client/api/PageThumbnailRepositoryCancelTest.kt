package com.lanraragi.reader.client.api

import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.module.CoroutineModule
import com.lanraragi.reader.stubNetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * A shared page-thumbnail fetch lives as long as someone awaits it: one
 * cancelled caller leaves it running for the others, and the last caller
 * leaving cancels and unregisters it (audit 2026-10-13 PERF-01), so an
 * abandoned grid's requests stop holding the fetch queue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PageThumbnailRepositoryCancelTest {

    private companion object {
        /** Longer than the first 202 backoff (1 s) plus request latency. */
        const val RETRY_WINDOW_MS = 2_500L
    }

    private val arcid = "c".repeat(40)
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        // Always "still generating": the fetch keeps polling until cancelled.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(202).setBody("""{"job":1}""")
        }
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        ServiceRegistry.initializeForTest(
            coroutine = CoroutineModule(),
            network = stubNetworkModule(OkHttpClient()),
        )
        scope = containedTestScope()
    }

    @After
    fun tearDown() {
        scope.cancel()
        awaitUntil(timeoutMs = 3_000) { PageThumbnailRepository.inFlightCount() == 0 }
        server.shutdown()
    }

    private fun load(): Job = scope.launch { PageThumbnailRepository.loadThumbnail(arcid, 0, baseUrl) }

    @Test(timeout = 30_000)
    fun lastCallerLeaving_cancelsAndUnregistersTheFetch() {
        val caller = load()
        server.awaitRequest()
        assertEquals(1, PageThumbnailRepository.inFlightCount())

        caller.cancel()

        awaitUntil(timeoutMs = 3_000, message = "abandoned fetch still registered") {
            PageThumbnailRepository.inFlightCount() == 0
        }
        // A live fetch re-polls the 202 after 1 s; a cancelled one never does.
        assertNull(
            "abandoned fetch kept polling the server",
            server.takeRequest(RETRY_WINDOW_MS, TimeUnit.MILLISECONDS),
        )
    }

    @Test(timeout = 30_000)
    fun oneOfTwoCallersLeaving_keepsTheSharedFetch() {
        val first = load()
        server.awaitRequest()
        val second = load()

        first.cancel()

        assertEquals(1, PageThumbnailRepository.inFlightCount())
        assertTrue(second.isActive)

        second.cancel()
        awaitUntil(timeoutMs = 3_000) { PageThumbnailRepository.inFlightCount() == 0 }
    }
}
