package com.hippo.ehviewer.client.lrr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import java.io.IOException
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LRRArchiveApiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    // ── updateMetadata (form-body PUT) ─────────────────────────────

    @Test
    fun updateMetadata_sendsCorrectRequest() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_metadata","success":1}"""))

        LRRArchiveApi.updateMetadata(client, baseUrl, "abc", tags = "artist:foo, parody:bar")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/archives/abc/metadata", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("tags="))
        assertTrue(body.contains("artist"))
    }

    @Test
    fun updateMetadata_withTitle_sendsTitle() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_metadata","success":1}"""))

        LRRArchiveApi.updateMetadata(client, baseUrl, "abc", title = "New Title", tags = "artist:test")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        val body = req.body.readUtf8()
        assertTrue(body.contains("title="))
        assertTrue(body.contains("tags="))
    }

    @Test
    fun updateMetadata_serverError_throwsHttpException() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            LRRArchiveApi.updateMetadata(client, baseUrl, "abc", tags = "test:tag")
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
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

    // ── uploadArchive ──────────────────────────────────────────────

    @Test
    fun uploadArchive_success() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1,"id":"new_arc_id"}"""))

        val testFile = tempFolder.newFile("test_upload.zip")
        testFile.writeBytes(ByteArray(100) { it.toByte() })

        val arcid = LRRArchiveApi.uploadArchive(client, baseUrl, testFile)
        assertEquals("new_arc_id", arcid)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/archives/upload", req.path)
        val contentType = req.getHeader("Content-Type")!!
        assertTrue(contentType.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("test_upload.zip"))
    }

    @Test
    fun uploadArchive_withAllParams() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1,"id":"new_id"}"""))

        val testFile = tempFolder.newFile("manga.cbz")
        testFile.writeBytes(ByteArray(50))

        val arcid = LRRArchiveApi.uploadArchive(
            client, baseUrl, testFile,
            title = "My Manga",
            tags = "artist:foo, language:en",
            categoryId = "cat42"
        )
        assertEquals("new_id", arcid)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("title"))
        assertTrue(body.contains("My Manga"))
        assertTrue(body.contains("tags"))
        assertTrue(body.contains("artist:foo"))
        assertTrue(body.contains("category_id"))
        assertTrue(body.contains("cat42"))
    }

    @Test
    fun uploadArchive_failure() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":0,"error":"File too large"}"""))

        val testFile = tempFolder.newFile("big.zip")
        testFile.writeBytes(ByteArray(10))

        try {
            LRRArchiveApi.uploadArchive(client, baseUrl, testFile)
            fail("Should have thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("File too large"))
        }
    }

    @Test
    fun uploadArchive_serverError() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500))
        }

        val testFile = tempFolder.newFile("err.zip")
        testFile.writeBytes(ByteArray(10))

        try {
            LRRArchiveApi.uploadArchive(client, baseUrl, testFile)
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }
}

