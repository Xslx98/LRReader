package com.hippo.ehviewer.client.lrr

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
import java.util.concurrent.TimeUnit

class LRRSearchApiTest {

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

    private val searchResponseJson = """{
        "data": [{"arcid":"a1","title":"Result","tags":"","isnew":"false","extension":"zip","filename":"r.zip","pagecount":1,"progress":0,"lastreadtime":0}],
        "draw": 1,
        "recordsFiltered": 1,
        "recordsTotal": 50
    }"""

    @Test
    fun searchArchives_allParams() = runTest {
        server.enqueue(MockResponse().setBody(searchResponseJson))

        val result = LRRSearchApi.searchArchives(
            client, baseUrl,
            filter = "test query",
            category = "cat1",
            start = 10,
            sortby = "date_added",
            order = "desc",
            newonly = true
        )
        assertEquals(1, result.data.size)
        assertEquals(50, result.recordsTotal)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        val path = req.path!!
        assertTrue(path.startsWith("/api/search"))
        assertTrue(path.contains("filter="))
        assertTrue(path.contains("category=cat1"))
        assertTrue(path.contains("start=10"))
        assertTrue(path.contains("sortby=date_added"))
        assertTrue(path.contains("order=desc"))
        assertTrue(path.contains("newonly=true"))
    }

    @Test
    fun searchArchives_minimalParams() = runTest {
        server.enqueue(MockResponse().setBody(searchResponseJson))

        LRRSearchApi.searchArchives(
            client, baseUrl,
            filter = null, category = null, start = 0,
            sortby = null, order = null, newonly = false
        )

        val req = server.takeRequest()
        val path = req.path!!
        assertEquals("/api/search", path)
    }

    @Test
    fun getRandomArchives_countParam() = runTest {
        server.enqueue(MockResponse().setBody(searchResponseJson))

        LRRSearchApi.getRandomArchives(client, baseUrl, filter = null, count = 5)

        val req = server.takeRequest()
        val path = req.path!!
        assertTrue(path.startsWith("/api/search/random"))
        assertTrue(path.contains("count=5"))
    }
}
