package com.hippo.ehviewer.client.lrr

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

class LRRDatabaseApiTest {

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

    // ── getDatabaseStats ───────────────────────────────────────────

    @Test
    fun getDatabaseStats_success() = runTest {
        val statsJson = """[
            {"namespace":"artist","text":"foo","weight":5},
            {"namespace":"date_added","text":"1700000","weight":10}
        ]"""
        server.enqueue(MockResponse().setBody(statsJson))

        val result = LRRDatabaseApi.getDatabaseStats(client, baseUrl)
        assertTrue(result.contains("artist"))
        assertTrue(result.contains("foo"))

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/database/stats", req.path)
    }

    @Test
    fun getDatabaseStats_emptyResult() = runTest {
        server.enqueue(MockResponse().setBody("[]"))

        val result = LRRDatabaseApi.getDatabaseStats(client, baseUrl)
        assertEquals("[]", result)
    }

    @Test
    fun getDatabaseStats_serverError() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal error"))
        }
        try {
            LRRDatabaseApi.getDatabaseStats(client, baseUrl)
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("服务器错误"))
        }
    }
}
