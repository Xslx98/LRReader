package com.hippo.ehviewer.client.lrr

import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class LRRArchiveApiTest {

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
    fun getArchiveMetadata_success() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "arcid":"abc","title":"Test Archive","tags":"artist:foo","isnew":"false",
            "extension":"zip","filename":"test.zip","pagecount":10,"progress":3,"lastreadtime":0
        }"""))

        val archive = LRRArchiveApi.getArchiveMetadata(client, baseUrl, "abc")
        assertEquals("abc", archive.arcid)
        assertEquals("Test Archive", archive.title)
        assertEquals(10, archive.pagecount)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/archives/abc/metadata", req.path)
    }

    @Test
    fun getFileList_success() = runTest {
        server.enqueue(MockResponse().setBody("""{"pages":["./page1.jpg","./page2.jpg","./page3.jpg"]}"""))

        val pages = LRRArchiveApi.getFileList(client, baseUrl, "abc")
        assertEquals(3, pages.size)
        assertEquals("./page1.jpg", pages[0])

        val req = server.takeRequest()
        assertEquals("/api/archives/abc/files", req.path)
    }

    @Test
    fun getFileList_missingPages() = runTest {
        server.enqueue(MockResponse().setBody("""{"job": 1}"""))
        try {
            LRRArchiveApi.getFileList(client, baseUrl, "abc")
            fail("Should have thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("pages"))
        }
    }

    @Test
    fun updateArchiveMetadata_sendsTags() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_metadata","success":1}"""))

        LRRArchiveApi.updateArchiveMetadata(client, baseUrl, "abc", "artist:test, rating:⭐⭐")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.contains("/api/archives/abc/metadata"))
        assertTrue(req.path!!.contains("tags="))
    }

    @Test
    fun clearNewFlag_sendsDelete() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"clear_new","success":1}"""))

        LRRArchiveApi.clearNewFlag(client, baseUrl, "abc")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/archives/abc/isnew", req.path)
    }

    @Test
    fun updateProgress_sendsPut() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_progress","success":1}"""))

        LRRArchiveApi.updateProgress(client, baseUrl, "abc", 5)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/archives/abc/progress/5", req.path)
    }

    @Test
    fun deleteArchive_success() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1,"filename":"deleted.zip"}"""))

        val filename = LRRArchiveApi.deleteArchive(client, baseUrl, "abc")
        assertEquals("deleted.zip", filename)

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/archives/abc", req.path)
    }

    @Test
    fun deleteArchive_failure() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":0,"error":"Not found"}"""))
        try {
            LRRArchiveApi.deleteArchive(client, baseUrl, "abc")
            fail("Should have thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Not found"))
        }
    }

    @Test
    fun getPageUrl_buildsCorrectUrl() {
        val url = LRRArchiveApi.getPageUrl("https://server.test", "abc", "./page 1.jpg")
        assertTrue(url.startsWith("https://server.test/api/archives/abc/page"))
        assertTrue(url.contains("path="))
    }
}
