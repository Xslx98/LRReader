package com.lanraragi.reader.client.api

import com.lanraragi.reader.awaitRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `PUT /api/archives/{id}/thumbnail?page=N` (spec 2026-09-22-tank-cover §3.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class LRRArchiveApiThumbnailPutTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var client: OkHttpClient
    private val arcid = "a".repeat(40)

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
    fun updateThumbnail_putsOneIndexedPage() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_thumbnail","success":1,"new_thumbnail":"x"}"""))

        LRRArchiveApi.updateThumbnail(client, baseUrl, arcid, page1 = 47)

        val req = server.awaitRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/archives/$arcid/thumbnail?page=47", req.path)
    }

    @Test
    fun updateThumbnail_rejectsPageBelowOne() = runTest {
        assertThrows(LRRClientValidationException::class.java) {
            kotlinx.coroutines.runBlocking { LRRArchiveApi.updateThumbnail(client, baseUrl, arcid, page1 = 0) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun updateThumbnail_surfacesServerError() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"page out of range"}"""))

        assertThrows(LRRHttpException::class.java) {
            kotlinx.coroutines.runBlocking { LRRArchiveApi.updateThumbnail(client, baseUrl, arcid, page1 = 999) }
        }
    }
}
