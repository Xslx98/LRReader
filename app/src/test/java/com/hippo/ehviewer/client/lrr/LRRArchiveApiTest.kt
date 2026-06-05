package com.lanraragi.reader.client.api

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
import java.io.ByteArrayInputStream
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
            "arcid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"Test Archive","tags":"artist:foo","isnew":"false",
            "extension":"zip","filename":"test.zip","pagecount":10,"progress":3,"lastreadtime":0
        }"""))

        val archive = LRRArchiveApi.getArchiveMetadata(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", archive.arcid)
        assertEquals("Test Archive", archive.title)
        assertEquals(10, archive.pagecount)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/archives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/metadata", req.path)
    }

    @Test
    fun getFileList_success() = runTest {
        server.enqueue(MockResponse().setBody("""{"pages":["./page1.jpg","./page2.jpg","./page3.jpg"]}"""))

        val pages = LRRArchiveApi.getFileList(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertEquals(3, pages.size)
        assertEquals("./page1.jpg", pages[0])

        val req = server.takeRequest()
        assertEquals("/api/archives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/files", req.path)
    }

    @Test
    fun getFileList_missingPages() = runTest {
        server.enqueue(MockResponse().setBody("""{"job": 1}"""))
        try {
            LRRArchiveApi.getFileList(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            fail("Should have thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("pages"))
        }
    }

    @Test
    fun updateArchiveMetadata_sendsTags() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_metadata","success":1}"""))

        LRRArchiveApi.updateArchiveMetadata(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "artist:test, rating:⭐⭐")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.contains("/api/archives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/metadata"))
        assertTrue(req.path!!.contains("tags="))
    }

    // ── updateMetadata (query-parameter PUT, per OpenAPI spec) ─────

    @Test
    fun updateMetadata_sendsCorrectRequest() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_metadata","success":1}"""))

        LRRArchiveApi.updateMetadata(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", tags = "artist:foo, parody:bar")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.startsWith("/api/archives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/metadata"))
        // Per spec, inputs travel as query parameters (no requestBody node).
        assertTrue("path should carry tags= query param", req.path!!.contains("tags="))
        assertTrue("path should encode the artist tag", req.path!!.contains("artist"))
        assertEquals("body must be empty for spec-compliant PUT", 0L, req.bodySize)
    }

    @Test
    fun updateMetadata_withTitle_sendsTitle() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_metadata","success":1}"""))

        LRRArchiveApi.updateMetadata(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", title = "New Title", tags = "artist:test")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue("path should carry title= query param", req.path!!.contains("title="))
        assertTrue("path should carry tags= query param", req.path!!.contains("tags="))
        assertEquals("body must be empty for spec-compliant PUT", 0L, req.bodySize)
    }

    @Test
    fun updateMetadata_withSummary_sendsSummary() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_metadata","success":1}"""))

        LRRArchiveApi.updateMetadata(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", summary = "A new summary line")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue("path should carry summary= query param", req.path!!.contains("summary="))
        // null fields are omitted entirely
        assertTrue("title should not be present when null", !req.path!!.contains("title="))
        assertTrue("tags should not be present when null", !req.path!!.contains("tags="))
        assertEquals(0L, req.bodySize)
    }

    @Test
    fun updateMetadata_serverError_throwsHttpException() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            LRRArchiveApi.updateMetadata(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", tags = "test:tag")
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun clearNewFlag_sendsDelete() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"clear_new","success":1}"""))

        LRRArchiveApi.clearNewFlag(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/archives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/isnew", req.path)
    }

    @Test
    fun updateProgress_sendsPut() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"update_progress","success":1}"""))

        LRRArchiveApi.updateProgress(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 5)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/archives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/progress/5", req.path)
    }

    @Test
    fun updateProgress_skipsWhenServerUsesClientsideTracking() = runTest {
        // Spec: check /api/info first. On a clientside-progress server this
        // endpoint 400s, so when a prior /api/info recorded
        // server_tracks_progress=false we skip the doomed call entirely.
        ServerCapabilityCache.setTracksProgress(baseUrl, false)
        try {
            server.enqueue(MockResponse().setBody("""{"operation":"update_progress","success":1}"""))
            LRRArchiveApi.updateProgress(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 5)
            assertEquals("no request should be sent on a clientside-progress server", 0, server.requestCount)
        } finally {
            ServerCapabilityCache.clear()
        }
    }

    @Test
    fun deleteArchive_success() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1,"filename":"deleted.zip"}"""))

        val filename = LRRArchiveApi.deleteArchive(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertEquals("deleted.zip", filename)

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/archives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", req.path)
    }

    @Test
    fun deleteArchive_failure() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":0,"error":"Not found"}"""))
        try {
            LRRArchiveApi.deleteArchive(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            fail("Should have thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Not found"))
        }
    }

    @Test
    fun getPageUrl_buildsCorrectUrl() {
        val url = LRRArchiveApi.getPageUrl("https://server.test", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "./page 1.jpg")
        assertTrue(url.startsWith("https://server.test/api/archives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/page"))
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
    fun uploadArchive_sendsSummaryAndChecksum() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1,"id":"x"}"""))

        val testFile = tempFolder.newFile("with_meta.zip")
        testFile.writeBytes(ByteArray(20))

        LRRArchiveApi.uploadArchive(
            client, baseUrl, testFile,
            summary = "A great manga",
            fileChecksum = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("summary"))
        assertTrue(body.contains("A great manga"))
        assertTrue(body.contains("file_checksum"))
        assertTrue(body.contains("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"))
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

    // ── per-page thumbnail (1-indexed on the wire) ─────────────────

    @Test
    fun fetchPageThumbnail_sendsOneIndexedPage() = runTest {
        // The grid passes 0-indexed page numbers; LANraragi stores per-page
        // thumbnails 1-indexed (1.jpg..N.jpg; page=0 is the cover), so the wire
        // value must be page + 1 — otherwise every tile is off by one and the
        // last page is never requested.
        server.enqueue(MockResponse().setResponseCode(200).setBody("imgbytes"))

        LRRArchiveApi.fetchPageThumbnail(
            client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0
        )

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        val path = req.path!!
        assertTrue("path: $path", path.startsWith("/api/archives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/thumbnail"))
        assertTrue("expected page=1 for grid index 0, path: $path", path.contains("page=1"))
        assertTrue(path.contains("no_fallback=true"))
    }

    @Test
    fun getPageThumbnailUrl_isOneIndexed() {
        val url = LRRArchiveApi.getPageThumbnailUrl(
            baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0
        )
        assertTrue("expected page=1 for grid index 0, url: $url", url.contains("page=1"))
    }

    // ── computeFileChecksum ─────────────────────────────────────────

    @Test
    fun computeFileChecksum_matchesKnownSha1() {
        val testFile = tempFolder.newFile("checksum.bin")
        testFile.writeBytes("hello".toByteArray())
        // SHA-1("hello") = aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d
        assertEquals(
            "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d",
            LRRArchiveApi.computeFileChecksum(testFile)
        )
    }

    // ── computeArchiveId (LANraragi arcid = SHA-1 of first 512000 bytes) ──

    @Test
    fun computeArchiveId_smallStream_isSha1OfWholeContent() {
        // For files <= 512000 bytes the arcid is just the SHA-1 of the full
        // content. SHA-1("hello") = aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d,
        // matching how LANraragi's compute_id hashes the leading bytes.
        val id = LRRArchiveApi.computeArchiveId(ByteArrayInputStream("hello".toByteArray()))
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", id)
    }

    @Test
    fun computeArchiveId_ignoresBytesPastFirst512000() {
        // Bytes beyond the 512000-byte window must not affect the id — this is
        // exactly what the server does, so a local pre-upload dedup check can
        // reproduce the arcid without reading the whole (large) file.
        val window = ByteArray(512_000) { 0x41 } // 512000 × 'A'
        val onlyWindow = LRRArchiveApi.computeArchiveId(ByteArrayInputStream(window))
        val windowPlusTail =
            LRRArchiveApi.computeArchiveId(ByteArrayInputStream(window + ByteArray(100) { 0x42 }))
        assertEquals(onlyWindow, windowPlusTail)
    }

    @Test
    fun computeArchiveId_includesByteAt512000Boundary() {
        // The 512000th byte IS part of the window: changing it changes the id.
        // Together with the previous test this pins the window to exactly 512000.
        val allA = LRRArchiveApi.computeArchiveId(ByteArrayInputStream(ByteArray(512_000) { 0x41 }))
        val lastByteDiffers = LRRArchiveApi.computeArchiveId(
            ByteArrayInputStream(ByteArray(511_999) { 0x41 } + byteArrayOf(0x42))
        )
        assertNotEquals(allA, lastByteDiffers)
    }

    // ── archiveExists (pre-upload duplicate probe) ──────────────────

    @Test
    fun archiveExists_returnsTrueWhenMetadataFound() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"arcid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"T","tags":"",
                   "isnew":"false","extension":"zip","filename":"t.zip","pagecount":1,
                   "progress":0,"lastreadtime":0}"""
            )
        )

        val exists = LRRArchiveApi.archiveExists(
            client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        )
        assertTrue(exists)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/archives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/metadata", req.path)
    }

    @Test
    fun archiveExists_returnsFalseOnBadRequest() = runTest {
        // LANraragi answers an unknown arcid with HTTP 400 (not 404).
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"success":0,"error":"This ID does not exist"}""")
        )
        assertFalse(
            LRRArchiveApi.archiveExists(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        )
    }

    @Test
    fun archiveExists_returnsFalseOnNotFound() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(
            LRRArchiveApi.archiveExists(client, baseUrl, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        )
    }
}

