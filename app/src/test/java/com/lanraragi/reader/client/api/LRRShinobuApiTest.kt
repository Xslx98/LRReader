package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
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

class LRRShinobuApiTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── getShinobuStatus ───────────────────────────────────────────

    @Test
    fun getShinobuStatus_success() = runTest {
        server.enqueue(MockResponse().setBody("""{"is_alive":true,"pid":12345}"""))

        val result = LRRShinobuApi.getShinobuStatus(client, baseUrl)
        assertTrue(result.contains("is_alive"))
        assertTrue(result.contains("12345"))

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/shinobu", req.path)
    }

    @Test
    fun getShinobuStatus_serverError() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500))
        }
        try {
            LRRShinobuApi.getShinobuStatus(client, baseUrl)
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    // ── restartShinobu ─────────────────────────────────────────────

    @Test
    fun restartShinobu_success() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"shinobu_restart","success":1}"""))

        LRRShinobuApi.restartShinobu(client, baseUrl)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/shinobu/restart", req.path)
    }

    @Test
    fun restartShinobu_serverError() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500))
        }
        try {
            LRRShinobuApi.restartShinobu(client, baseUrl)
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }
}
