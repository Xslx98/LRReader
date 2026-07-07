package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
    fun friendlyError_callTimeoutInterruptedIOException() {
        // OkHttp callTimeout throws a bare InterruptedIOException, which must
        // map to the same localized timeout message as SocketTimeoutException.
        val msg = friendlyError(ctx, java.io.InterruptedIOException("timeout"))
        assertEquals(ctx.getString(R.string.lrr_timeout_error), msg)
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

    // ── resolvePageUrl ─────────────────────────────────────────────

    @Test
    fun resolvePageUrl_absolutePath_noTrailingSlashBase() {
        assertEquals(
            "http://host:3000/api/archives/abc/page?path=a.jpg",
            resolvePageUrl("http://host:3000", "/api/archives/abc/page?path=a.jpg")
        )
    }

    @Test
    fun resolvePageUrl_documentRelativeDotSlash() {
        // The shape the project's own fixtures and LANraragi's docs can return.
        // Bare concatenation would yield "http://host:3000./api/..." (broken).
        assertEquals(
            "http://host:3000/api/archives/abc/page?path=a.jpg",
            resolvePageUrl("http://host:3000", "./api/archives/abc/page?path=a.jpg")
        )
    }

    @Test
    fun resolvePageUrl_bareRelativeAgainstTrailingSlashBase() {
        assertEquals(
            "http://host:3000/api/archives/abc/page",
            resolvePageUrl("http://host:3000/", "api/archives/abc/page")
        )
    }

    @Test
    fun resolvePageUrl_alreadyAbsoluteUrlPassesThrough() {
        assertEquals(
            "http://other:9000/api/x",
            resolvePageUrl("http://host:3000", "http://other:9000/api/x")
        )
    }

    @Test
    fun resolvePageUrl_preservesEncodedPath() {
        assertEquals(
            "http://host:3000/api/x?path=sub%20dir/a.jpg",
            resolvePageUrl("http://host:3000", "/api/x?path=sub%20dir/a.jpg")
        )
    }

    @Test
    fun resolvePageUrl_invalidServerUrlThrows() {
        try {
            resolvePageUrl("not a url", "/api/x")
            fail("Should have thrown")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun resolvePageUrl_subpathBase_absolutePathKeepsPrefix() {
        // Reverse-proxy sub-path deployment: a root-absolute page path means
        // "relative to the LANraragi mount", not the host root. The /lanraragi
        // prefix must survive (plain HttpUrl.resolve would drop it).
        assertEquals(
            "http://host/lanraragi/api/archives/abc/page?path=a.jpg",
            resolvePageUrl("http://host/lanraragi", "/api/archives/abc/page?path=a.jpg")
        )
    }

    @Test
    fun resolvePageUrl_subpathBase_dotSlashKeepsPrefix() {
        // "./" shape against a no-trailing-slash sub-path base: document-
        // relative resolution would drop the last base segment; ours must not.
        assertEquals(
            "http://host/lanraragi/api/archives/abc/page?path=a.jpg",
            resolvePageUrl("http://host/lanraragi", "./api/archives/abc/page?path=a.jpg")
        )
    }

    @Test
    fun resolvePageUrl_subpathBaseWithTrailingSlash() {
        assertEquals(
            "http://host/lanraragi/api/x",
            resolvePageUrl("http://host/lanraragi/", "/api/x")
        )
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

    @Test
    fun retryOnFailure_doesNotRetryOn401() = runTest {
        var callCount = 0
        try {
            retryOnFailure(maxRetries = 2) {
                callCount++
                throw LRRHttpException(401)
            }
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(401, e.code)
        }
        assertEquals("Should not retry on 401", 1, callCount)
    }

    @Test
    fun retryOnFailure_doesNotRetryOn404() = runTest {
        var callCount = 0
        try {
            retryOnFailure(maxRetries = 2) {
                callCount++
                throw LRRHttpException(404)
            }
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(404, e.code)
        }
        assertEquals("Should not retry on 404", 1, callCount)
    }

    @Test
    fun retryOnFailure_alwaysFails_rethrowsLastException() = runTest {
        val expectedException = IOException("persistent failure")
        var callCount = 0
        try {
            retryOnFailure(maxRetries = 2) {
                callCount++
                throw expectedException
            }
            fail("Should have thrown")
        } catch (e: IOException) {
            assertSame("Should rethrow the exact last exception instance", expectedException, e)
        }
        advanceUntilIdle()
        assertEquals(3, callCount) // 1 initial + 2 retries
    }

    @Test
    fun retryOnFailure_lastExceptionAlwaysNonNull_fallbackNeverReached() = runTest {
        // Document that the fallback IOException("Retry exhausted...") is unreachable
        // under current control flow: repeat() guarantees at least one catch executes
        // before the throw line, so lastException is always non-null.
        var callCount = 0
        try {
            retryOnFailure(maxRetries = 0) {
                callCount++
                throw IOException("single attempt")
            }
            fail("Should have thrown")
        } catch (e: IOException) {
            // If the fallback were reached, the message would be "Retry exhausted after 1 attempts"
            assertEquals("single attempt", e.message)
        }
        assertEquals(1, callCount) // maxRetries=0 means 1 attempt total
    }

    @Test
    fun retryOnFailure_retriesOn503() = runTest {
        var callCount = 0
        try {
            retryOnFailure(maxRetries = 2) {
                callCount++
                throw LRRHttpException(503)
            }
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(503, e.code)
        }
        advanceUntilIdle()
        assertEquals("Should retry on 503", 3, callCount) // 1 initial + 2 retries
    }

    @Test
    fun retryOnFailure_postCancellationIOException_surfacesAsCancellationNotIOException() = runTest {
        // After call.cancel(), OkHttp throws IOException("Canceled"). On the FINAL
        // attempt there is no backoff delay() left to convert it, so without the
        // ensureActive() guard the dead coroutine reports IOException — callers
        // would show a phantom network error for a deliberate cancellation.
        var attempts = 0
        var outcome: String? = null
        val job = launch {
            outcome = try {
                retryOnFailure(maxRetries = 0) {
                    attempts++
                    coroutineContext.job.cancel() // simulate: cancellation arrives mid-request
                    throw IOException("Canceled")
                }
                "success"
            } catch (e: CancellationException) {
                "cancelled"
            } catch (e: IOException) {
                "ioexception"
            }
        }
        job.join()
        assertEquals("cancelled", outcome)
        assertEquals(1, attempts)
    }

}
