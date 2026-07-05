package com.lanraragi.reader.client.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LRRTankoubonApiTest {

    private val tankId = "TANK_1688616437"
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
    fun getTankoubons_parsesNewFieldsAndCounts() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"result":[{"id":"$tankId","name":"Series A","archives":["$arcid"],
                   "summary":"s","tags":"artist:x","progress":12}],"total":3,"filtered":1}"""
            )
        )

        val r = LRRTankoubonApi.getTankoubons(client, baseUrl)

        assertEquals(1, r.result.size)
        assertEquals("Series A", r.result[0].name)
        assertEquals("s", r.result[0].summary)
        assertEquals(12, r.result[0].progress)
        assertEquals(3, r.total)
        assertEquals(1, r.filtered)
        assertEquals("/api/tankoubons", server.takeRequest().path)
    }

    @Test
    fun getTankoubonFull_parsesMembersAndMeta() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"id":"$tankId","name":"Series A","summary":"s","tags":"",
                   "progress":5,"archives":["$arcid"],
                   "full_data":[{"arcid":"$arcid","title":"Vol 1","tags":"","isnew":"false",
                   "extension":"zip","filename":"v1.zip","pagecount":20,"progress":0,"lastreadtime":0}]},
                   "total":1,"filtered":1}"""
            )
        )

        val r = LRRTankoubonApi.getTankoubonFull(client, baseUrl, tankId)

        assertEquals("Series A", r.result.name)
        assertEquals(5, r.result.progress)
        assertEquals(listOf(arcid), r.result.archives)
        assertEquals(1, r.result.fullData.size)
        assertEquals(20, r.result.fullData[0].pagecount)
        val req = server.takeRequest()
        assertEquals("/api/tankoubons/$tankId/full?page=-1", req.path)
    }

    @Test
    fun getTankoubonFull_404_throwsHttpException() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            LRRTankoubonApi.getTankoubonFull(client, baseUrl, tankId)
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun getArchiveTankoubons_parsesIds() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"operation":"find_arc_tankoubons","tankoubons":["$tankId"],"success":1}"""
            )
        )

        val ids = LRRTankoubonApi.getArchiveTankoubons(client, baseUrl, arcid)

        assertEquals(listOf(tankId), ids)
        assertEquals("/api/archives/$arcid/tankoubons", server.takeRequest().path)
    }

    @Test
    fun getTankoubonThumbnailUrl_buildsTankRoute() {
        val url = LRRTankoubonApi.getTankoubonThumbnailUrl("http://h:3000", tankId)
        assertTrue(url.endsWith("/api/tankoubons/$tankId/thumbnail"))
    }

    @Test
    fun invalidTankId_throwsClientValidation() = runTest {
        try {
            LRRTankoubonApi.getTankoubonFull(client, baseUrl, "not_a_tank")
            fail("Should have thrown")
        } catch (expected: LRRClientValidationException) {
            // no request must have been sent
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun getTankoubons_withPage_sendsPageQuery() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":[],"total":0,"filtered":0}"""))

        LRRTankoubonApi.getTankoubons(client, baseUrl, page = 3)

        assertEquals("/api/tankoubons?page=3", server.takeRequest().path)
    }

    @Test
    fun getTankoubons_pageZero_omitsQuery() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":[],"total":0,"filtered":0}"""))

        LRRTankoubonApi.getTankoubons(client, baseUrl, page = 0)

        assertEquals("/api/tankoubons", server.takeRequest().path)
    }

    @Test
    fun getTankoubons_emptyResult() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":[],"total":0,"filtered":0}"""))

        val r = LRRTankoubonApi.getTankoubons(client, baseUrl)

        assertTrue(r.result.isEmpty())
        assertEquals(0, r.total)
    }

    @Test
    fun getTankoubons_serverError_throwsHttpException() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            LRRTankoubonApi.getTankoubons(client, baseUrl)
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun getTankoubonThumbnailUrl_cacheBust_appendsTsParam() {
        val url = LRRTankoubonApi.getTankoubonThumbnailUrl("http://h:3000", tankId, cacheBust = 5L)
        assertTrue(url.contains("?ts=5"))
    }

    @Test
    fun getTankoubonThumbnailUrl_defaultCacheBust_omitsTsParam() {
        val url = LRRTankoubonApi.getTankoubonThumbnailUrl("http://h:3000", tankId)
        assertFalse(url.contains("ts="))
    }

    @Test
    fun wrongLengthTankId_throwsClientValidation() = runTest {
        try {
            LRRTankoubonApi.getTankoubonFull(client, baseUrl, "TANK_123")
            fail("Should have thrown")
        } catch (expected: LRRClientValidationException) {
            // no request must have been sent
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun nonDigitTankId_throwsClientValidation() = runTest {
        try {
            LRRTankoubonApi.getTankoubonFull(client, baseUrl, "TANK_16886164a7")
            fail("Should have thrown")
        } catch (expected: LRRClientValidationException) {
            // no request must have been sent
            assertEquals(0, server.requestCount)
        }
    }
}
