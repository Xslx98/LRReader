package com.hippo.ehviewer.client.lrr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LRRApiUtilsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    // ── ensureSuccess ──────────────────────────────────────────────

    @Test
    fun ensureSuccess_200() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response) // must not throw
        }
    }

    @Test
    fun ensureSuccess_401() {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun ensureSuccess_403() {
        server.enqueue(MockResponse().setResponseCode(403))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(403, e.code)
        }
    }

    @Test
    fun ensureSuccess_404() {
        server.enqueue(MockResponse().setResponseCode(404))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun ensureSuccess_500() {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun ensureSuccess_502() {
        server.enqueue(MockResponse().setResponseCode(502))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(502, e.code)
        }
    }

    @Test
    fun ensureSuccess_unknownCode() {
        server.enqueue(MockResponse().setResponseCode(418))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(418, e.code)
        }
    }

    @Test
    fun ensureSuccess_htmlBodyDoesNotLeakIntoMessage() {
        // Body is never read — ensureSuccess() throws LRRHttpException(code) immediately.
        // HTML cannot appear in the exception message regardless of response body content.
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("<html><body><h1>Service Unavailable</h1></body></html>")
                .addHeader("Content-Type", "text/html")
        )
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response -> ensureSuccess(response) }
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(503, e.code)
            assertFalse("HTTP exception message must not contain HTML", e.message!!.contains("<html"))
        }
    }

    // ── friendlyError ──────────────────────────────────────────────

    @Test
    fun friendlyError_httpException_401() {
        val msg = friendlyError(ctx, LRRHttpException(401))
        assertFalse(msg.isBlank())
    }

    @Test
    fun friendlyError_httpException_404() {
        val msg = friendlyError(ctx, LRRHttpException(404))
        assertFalse(msg.isBlank())
    }

    @Test
    fun friendlyError_httpException_503() {
        val msg = friendlyError(ctx, LRRHttpException(503))
        assertFalse(msg.isBlank())
    }

    @Test
    fun friendlyError_httpException_unknownCode() {
        val msg = friendlyError(ctx, LRRHttpException(418))
        assertFalse(msg.isBlank())
        assertTrue("Should include HTTP code", msg.contains("418"))
    }

    @Test
    fun friendlyError_timeout() {
        val msg = friendlyError(ctx, SocketTimeoutException("timeout"))
        assertFalse(msg.isBlank())
    }

    @Test
    fun friendlyError_connect() {
        val msg = friendlyError(ctx, ConnectException("refused"))
        assertFalse(msg.isBlank())
    }

    @Test
    fun friendlyError_dns() {
        val msg = friendlyError(ctx, UnknownHostException("bad.host"))
        assertFalse(msg.isBlank())
    }

    @Test
    fun friendlyError_ssl() {
        val msg = friendlyError(ctx, SSLException("handshake failed"))
        assertFalse(msg.isBlank())
    }

    @Test
    fun friendlyError_emptyBodyException() {
        val msg = friendlyError(ctx, LRREmptyBodyException())
        assertEquals(ctx.getString(R.string.lrr_empty_response), msg)
    }

    @Test
    fun friendlyError_missingFieldException() {
        val msg = friendlyError(ctx, LRRMissingFieldException("pages"))
        assertEquals(ctx.getString(R.string.lrr_malformed_response), msg)
    }

    @Test
    fun friendlyError_unknownException_passesMessageThrough() {
        val msg = friendlyError(ctx, RuntimeException("custom error"))
        assertEquals("custom error", msg)
    }

    // ── retryOnFailure ─────────────────────────────────────────────

    @Test
    fun retryOnFailure_succeedsFirstTime() = runTest {
        var callCount = 0
        val result = retryOnFailure {
            callCount++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, callCount)
    }

    @Test
    fun retryOnFailure_succeedsAfterRetry() = runTest {
        var callCount = 0
        val result = retryOnFailure(maxRetries = 2) {
            callCount++
            if (callCount < 2) throw IOException("fail")
            "ok"
        }
        advanceUntilIdle()
        assertEquals("ok", result)
        assertEquals(2, callCount)
    }

    @Test
    fun retryOnFailure_exhaustsRetries() = runTest {
        var callCount = 0
        try {
            retryOnFailure(maxRetries = 2) {
                callCount++
                throw IOException("always fail")
            }
            fail("Should have thrown")
        } catch (e: IOException) {
            assertEquals("always fail", e.message)
        }
        advanceUntilIdle()
        assertEquals(3, callCount) // 1 initial + 2 retries
    }
}
