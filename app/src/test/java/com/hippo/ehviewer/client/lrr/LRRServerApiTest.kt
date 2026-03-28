package com.hippo.ehviewer.client.lrr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LRRServerApiTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getServerInfo_success() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "name": "TestServer",
            "version": "0.9.21",
            "version_name": "Test",
            "has_password": false,
            "debug_mode": false,
            "nofun_mode": false,
            "archives_per_page": 100,
            "server_resizes_images": false,
            "server_tracks_progress": true,
            "cache_last_cleared": 0
        }"""))

        val info = LRRServerApi.getServerInfo(client, baseUrl)
        assertEquals("TestServer", info.name)
        assertEquals("0.9.21", info.version)
        assertTrue(info.serverTracksProgress)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/info", request.path)
    }

    @Test
    fun getServerInfo_httpError() = runTest {
        // retryOnFailure does 2 retries + 1 initial = 3 total
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        }
        try {
            LRRServerApi.getServerInfo(client, baseUrl)
            fail("Should have thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("服务器错误"))
        }
    }

    @Test
    fun getServerInfo_requestPath() = runTest {
        server.enqueue(MockResponse().setBody("""{"name":"Test","version":"1.0"}"""))

        LRRServerApi.getServerInfo(client, baseUrl)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/info", req.path)
    }
}
