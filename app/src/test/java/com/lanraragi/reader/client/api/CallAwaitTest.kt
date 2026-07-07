package com.lanraragi.reader.client.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallAwaitTest {

    private lateinit var server: MockWebServer

    // Long timeouts on purpose: the cancellation test must prove that
    // cancel() — not a read timeout — unblocked the call.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request() = Request.Builder().url(server.url("/x")).build()

    @Test
    fun await_success_returnsResponseWithReadableBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":1}"""))
        client.newCall(request()).await().use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"ok":1}""", response.body?.string())
        }
    }

    @Test
    fun await_networkFailure_propagatesIOException() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        var thrown: Throwable? = null
        try {
            client.newCall(request()).await().use { }
        } catch (e: IOException) {
            thrown = e
        }
        assertTrue("expected IOException, got $thrown", thrown is IOException)
    }

    @Test
    fun await_cancellation_cancelsUnderlyingCallAndReturnsFast() = runTest {
        // Server never responds; only cancel() can unblock within the 30s timeouts.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = client.newCall(request())
        var outcome: String? = null
        val job = launch(Dispatchers.IO) {
            outcome = try {
                call.await().use { "response" }
            } catch (e: CancellationException) {
                "cancelled"
            } catch (e: IOException) {
                "ioexception"
            }
        }
        // Poll the exact precondition: the call must be in flight before cancelling.
        val deadline = System.currentTimeMillis() + 5000
        while (!call.isExecuted() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("call never started executing", call.isExecuted())
        val startNs = System.nanoTime()
        job.cancel()
        job.join()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertTrue("underlying call must be cancelled", call.isCanceled())
        assertEquals("cancelled", outcome)
        assertTrue("cancel should unblock fast, took ${elapsedMs}ms", elapsedMs < 5000)
    }
}
