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

class LRRTankoubonApiTest {

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

    // ── getTankoubons ──────────────────────────────────────────────

    @Test
    fun getTankoubons_success() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "result": [
                {"id":"tk1","name":"Volume 1","archives":["a1","a2","a3"]},
                {"id":"tk2","name":"Volume 2","archives":["a4","a5"]}
            ]
        }"""))

        val result = LRRTankoubonApi.getTankoubons(client, baseUrl)
        assertEquals(2, result.result.size)

        val v1 = result.result[0]
        assertEquals("tk1", v1.id)
        assertEquals("Volume 1", v1.name)
        assertEquals(listOf("a1", "a2", "a3"), v1.archives)

        val v2 = result.result[1]
        assertEquals("tk2", v2.id)
        assertEquals(2, v2.archives.size)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/tankoubons", req.path)
    }

    @Test
    fun getTankoubons_withPage() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":[]}"""))

        LRRTankoubonApi.getTankoubons(client, baseUrl, page = 3)

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("page=3"))
    }

    @Test
    fun getTankoubons_pageZeroIgnored() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":[]}"""))

        LRRTankoubonApi.getTankoubons(client, baseUrl, page = 0)

        val req = server.takeRequest()
        assertFalse(req.path!!.contains("page="))
    }

    @Test
    fun getTankoubons_emptyResult() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":[]}"""))

        val result = LRRTankoubonApi.getTankoubons(client, baseUrl)
        assertTrue(result.result.isEmpty())
    }

    @Test
    fun getTankoubons_emptyArchives() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "result": [{"id":"tk1","name":"Empty","archives":[]}]
        }"""))

        val result = LRRTankoubonApi.getTankoubons(client, baseUrl)
        assertEquals(1, result.result.size)
        assertTrue(result.result[0].archives.isEmpty())
    }

    @Test
    fun getTankoubons_serverError() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500))
        }
        try {
            LRRTankoubonApi.getTankoubons(client, baseUrl)
            fail("Should have thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("服务器错误"))
        }
    }

    // ── Data class tests ───────────────────────────────────────────

    @Test
    fun tankoubon_defaults() {
        val t = LRRTankoubonApi.Tankoubon()
        assertEquals("", t.id)
        assertEquals("", t.name)
        assertTrue(t.archives.isEmpty())
    }

    @Test
    fun tankoubonListResult_defaults() {
        val r = LRRTankoubonApi.TankoubonListResult()
        assertTrue(r.result.isEmpty())
    }
}
