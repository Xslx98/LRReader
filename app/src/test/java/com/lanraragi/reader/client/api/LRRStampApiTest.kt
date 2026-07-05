package com.lanraragi.reader.client.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
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

    @Test
    fun addStamp_putWithQuery_returnsStampId() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"operation":"add_stamp","stamp_id":"STAMPS_2_1719999999999","success":1}""")
        )

        val id = LRRStampApi.addStamp(client, baseUrl, arcid, page1 = 2, content = "täst text", position = "10.00,20.50")

        assertEquals("STAMPS_2_1719999999999", id)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/archives/$arcid/stamps/2", req.requestUrl!!.encodedPath)
        assertEquals("täst text", req.requestUrl!!.queryParameter("content"))
        assertEquals("10.00,20.50", req.requestUrl!!.queryParameter("position"))
    }

    @Test
    fun addStamp_successZero_throws() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"operation":"add_stamp","success":0,"error":"Page 99 out of range."}""")
        )
        try {
            LRRStampApi.addStamp(client, baseUrl, arcid, 2, "x", "1,1")
            fail("Should have thrown")
        } catch (e: IOException) {
            // expected: server's in-body error message surfaced verbatim
            assertTrue(e.message!!.contains("Page 99 out of range."))
        }
    }

    @Test
    fun addStamp_successOne_missingId_throwsMissingField() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"add_stamp","success":1}"""))
        try {
            LRRStampApi.addStamp(client, baseUrl, arcid, 2, "x", "1,1")
            fail("Should have thrown")
        } catch (e: LRRMissingFieldException) {
            // expected: contract violation — success:1 without a stamp_id
        }
    }

    @Test
    fun updateStamp_blankId_failsFast() = runTest {
        try {
            LRRStampApi.updateStamp(client, baseUrl, " ", content = "x")
            fail("Should have thrown")
        } catch (e: LRRClientValidationException) {
            // expected: blank stamp id rejected client-side
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun updateStamp_onlyContent_omitsPosition() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_stamp","success":1}"""))

        LRRStampApi.updateStamp(client, baseUrl, "STAMPS_2_1719999999999", content = "new", position = null)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/stamps/STAMPS_2_1719999999999", req.requestUrl!!.encodedPath)
        assertEquals("new", req.requestUrl!!.queryParameter("content"))
        assertEquals(null, req.requestUrl!!.queryParameter("position"))
    }

    @Test
    fun updateStamp_423_surfacesLockError() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(423)
                .setBody("""{"operation":"update_stamp","error":"Stamp locked","success":0}""")
        )
        try {
            LRRStampApi.updateStamp(client, baseUrl, "STAMPS_2_1", position = "1,1")
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(423, e.code)
            assertEquals("Stamp locked", e.serverError)
        }
    }

    @Test
    fun deleteStamp_sendsDelete() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"delete_stamp","success":1}"""))

        LRRStampApi.deleteStamp(client, baseUrl, "STAMPS_2_1719999999999")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/stamps/STAMPS_2_1719999999999", req.requestUrl!!.encodedPath)
    }
}
