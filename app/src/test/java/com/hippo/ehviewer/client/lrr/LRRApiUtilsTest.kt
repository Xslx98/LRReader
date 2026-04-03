package com.hippo.ehviewer.client.lrr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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

    // ── ensureSuccess ──────────────────────────────────────────────

    @Test
    fun ensureSuccess_200() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }

    @Test
    fun ensureSuccess_401() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response ->
                ensureSuccess(response)
            }
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("认证失败"))
        }
    }

    @Test
    fun ensureSuccess_404() {
        server.enqueue(MockResponse().setResponseCode(404))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response ->
                ensureSuccess(response)
            }
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("资源未找到"))
        }
    }

    @Test
    fun ensureSuccess_500() {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response ->
                ensureSuccess(response)
            }
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("服务器错误"))
        }
    }

    @Test
    fun ensureSuccess_502() {
        server.enqueue(MockResponse().setResponseCode(502))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response ->
                ensureSuccess(response)
            }
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("服务器错误"))
        }
    }

    @Test
    fun ensureSuccess_403() {
        server.enqueue(MockResponse().setResponseCode(403))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response ->
                ensureSuccess(response)
            }
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("认证失败"))
        }
    }

    @Test
    fun ensureSuccess_unknownCode() {
        server.enqueue(MockResponse().setResponseCode(418))
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response ->
                ensureSuccess(response)
            }
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("请求失败"))
            assertTrue(e.message!!.contains("418"))
        }
    }

    // ── friendlyError ──────────────────────────────────────────────

    @Test
    fun friendlyError_timeout() {
        val msg = friendlyError(SocketTimeoutException("timeout"))
        assertTrue(msg.contains("连接超时"))
    }

    @Test
    fun friendlyError_connect() {
        val msg = friendlyError(ConnectException("refused"))
        assertTrue(msg.contains("无法连接"))
    }

    @Test
    fun friendlyError_dns() {
        val msg = friendlyError(UnknownHostException("bad.host"))
        assertTrue(msg.contains("无法解析"))
    }

    @Test
    fun friendlyError_ssl() {
        val msg = friendlyError(SSLException("handshake failed"))
        assertTrue(msg.contains("安全连接失败"))
    }

    @Test
    fun friendlyError_passthrough() {
        val msg = friendlyError(RuntimeException("custom error"))
        assertEquals("custom error", msg)
    }

    @Test
    fun friendlyError_authMessage() {
        // Messages that start with known prefixes should pass through
        val msg = friendlyError(IOException("认证失败，请检查 API Key 是否正确"))
        assertTrue(msg.startsWith("认证失败"))
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
    fun ensureSuccess_htmlBodyDoesNotLeakIntoMessage() {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("<html><body><h1>Service Unavailable</h1></body></html>")
                .addHeader("Content-Type", "text/html")
        )
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(server.url("/")).build()
        try {
            client.newCall(request).execute().use { response ->
                ensureSuccess(response)
            }
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            assertEquals("服务器错误 (503)，请稍后重试", e.message)
        }
    }
}
