package com.lanraragi.reader.client.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ThumbnailCacheControlInterceptor].
 *
 * LANraragi sends no Cache-Control on thumbnail responses, so the interceptor
 * injects freshness headers — but ONLY on successful responses. Injecting on
 * errors lets OkHttp's cache pin a 404/410 thumbnail as fresh for an hour,
 * so broken thumbs survive server recovery.
 */
class ThumbnailCacheControlInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addNetworkInterceptor(ThumbnailCacheControlInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(path: String): okhttp3.Response {
        val request = Request.Builder().url(server.url(path)).build()
        return client.newCall(request).execute().apply { close() }
    }

    @Test
    fun `successful thumbnail response gets freshness headers`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("img"))
        val resp = get("/api/archives/abc123/thumbnail")
        assertEquals(
            "public, max-age=3600, stale-while-revalidate=82800",
            resp.header("Cache-Control"),
        )
    }

    @Test
    fun `error thumbnail response is not made cacheable`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("no such archive"))
        val resp = get("/api/archives/abc123/thumbnail")
        assertNull(resp.header("Cache-Control"))
    }

    @Test
    fun `server-error thumbnail response is not made cacheable`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("proxy error"))
        val resp = get("/api/archives/abc123/thumbnail")
        assertNull(resp.header("Cache-Control"))
    }

    @Test
    fun `page request with thumbnail in query path is untouched`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("page"))
        val resp = get("/api/archives/abc123/page?path=Vol1/thumbnail.jpg")
        assertNull(resp.header("Cache-Control"))
    }

    @Test
    fun `non-archive thumbnail path is untouched`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x"))
        val resp = get("/api/other/thumbnail")
        assertNull(resp.header("Cache-Control"))
    }

    @Test
    fun `pragma is stripped from successful thumbnail response`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("img").addHeader("Pragma", "no-cache"),
        )
        val resp = get("/api/archives/abc123/thumbnail")
        assertNull(resp.header("Pragma"))
    }
}
