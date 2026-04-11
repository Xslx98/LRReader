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

class LRRMiscApiTest {

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
    fun downloadUrl_success() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1,"job":42,"operation":"download_url"}"""))

        val jobId = LRRMiscApi.downloadUrl(client, baseUrl, "https://example.com/file.zip", catid = "cat1")
        assertEquals(42, jobId)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.contains("/api/download_url"))
        assertTrue(req.path!!.contains("url="))
        assertTrue(req.path!!.contains("catid=cat1"))
    }

    @Test(expected = IOException::class)
    fun downloadUrl_failure() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":0,"error":"Invalid URL"}"""))
        LRRMiscApi.downloadUrl(client, baseUrl, "bad-url")
    }

    @Test
    fun downloadUrl_noCatid() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1,"job":1}"""))

        LRRMiscApi.downloadUrl(client, baseUrl, "https://example.com/file.zip")

        val req = server.takeRequest()
        assertFalse(req.path!!.contains("catid"))
    }
}
