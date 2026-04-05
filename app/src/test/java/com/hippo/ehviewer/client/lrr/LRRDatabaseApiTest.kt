package com.hippo.ehviewer.client.lrr

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.hippo.ehviewer.client.lrr.LRRHttpException
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
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    // ── getTagStats (typed) ───────────────────────────────────────

    @Test
    fun getTagStats_success() = runTest {
        val statsJson = """[
            {"namespace":"artist","text":"foo","weight":5},
            {"namespace":"parody","text":"bar","weight":10}
        ]"""
        server.enqueue(MockResponse().setBody(statsJson))

        val result = LRRDatabaseApi.getTagStats(client, baseUrl)
        assertEquals(2, result.size)
        assertEquals("artist", result[0].namespace)
        assertEquals("foo", result[0].text)
        assertEquals(5, result[0].weight)
        assertEquals("parody", result[1].namespace)
        assertEquals("bar", result[1].text)
        assertEquals(10, result[1].weight)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/database/stats", req.path)
    }

    @Test
    fun getTagStats_emptyArray() = runTest {
        server.enqueue(MockResponse().setBody("[]"))

        val result = LRRDatabaseApi.getTagStats(client, baseUrl)
        assertTrue(result.isEmpty())
    }

    @Test
    fun getTagStats_serverError() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal error"))
        }
        try {
            LRRDatabaseApi.getTagStats(client, baseUrl)
            fail("Should have thrown LRRHttpException")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun getTagStats_emptyBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        try {
            LRRDatabaseApi.getTagStats(client, baseUrl)
            fail("Should have thrown")
        } catch (_: Exception) {
            // LRREmptyBodyException or serialization error — both acceptable
        }
    }
}
