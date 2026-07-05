package com.lanraragi.reader.client.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LRRStampApiTest {

    private val arcid = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
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

    @Test
    fun getStampedPages_parsesAndSkipsMalformed() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":["3","1","junk",""]}"""))

        val pages = LRRStampApi.getStampedPages(client, baseUrl, arcid)

        assertEquals(listOf(3, 1), pages)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/archives/$arcid/stamps", req.path)
    }

    @Test
    fun getStampedPages_404_throwsHttpException() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            LRRStampApi.getStampedPages(client, baseUrl, arcid)
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun getStampsByPage_parsesStamps_oneIndexedPath() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"result":[{"id":"STAMPS_5_1719999999999","position":"12.5,34","content":"hello"}]}"""
            )
        )

        val stamps = LRRStampApi.getStampsByPage(client, baseUrl, arcid, page1 = 5)

        assertEquals(1, stamps.size)
        assertEquals("STAMPS_5_1719999999999", stamps[0].id)
        assertEquals("12.5,34", stamps[0].position)
        assertEquals("hello", stamps[0].content)
        assertEquals("/api/archives/$arcid/stamps/5", server.takeRequest().path)
    }

    @Test
    fun getStampsByPage_emptyResult() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":[]}"""))
        assertEquals(emptyList<LRRStampApi.StampData>(), LRRStampApi.getStampsByPage(client, baseUrl, arcid, 1))
    }
}
