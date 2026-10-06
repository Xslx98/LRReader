package com.lanraragi.reader.client

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit SEC-01: the tag dataset download is size-capped, both on the
 * declared Content-Length and on the bytes actually streamed (a chunked
 * response declares none).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TagTranslationDownloadCapTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("tagcap").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun save(maxBytes: Long): Boolean = runBlocking {
        TagTranslationDatabase.save(client, server.url("/data").toString(), File(dir, "out"), maxBytes)
    }

    @Test(timeout = 10_000)
    fun body_at_the_cap_is_saved() {
        val body = ByteArray(100) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        assertTrue(save(100))
        assertArrayEquals(body, File(dir, "out").readBytes())
    }

    @Test(timeout = 10_000)
    fun declared_length_over_the_cap_is_refused() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(101))))
        assertFalse(save(100))
        // Refused on the header, before any byte is written.
        assertFalse(File(dir, "out").exists())
    }

    @Test(timeout = 10_000)
    fun chunked_body_over_the_cap_is_refused() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(10_000)), 512))
        assertFalse(save(100))
    }

    @Test
    fun caps_leave_headroom_over_the_real_dataset() {
        // 2026-10 dataset: 1,459,588 bytes; the hash file is a raw 20-byte SHA-1.
        assertTrue(TagTranslationDatabase.MAX_DATASET_DOWNLOAD_BYTES > 1_459_588L * 5)
        assertEquals(TagTranslationDatabase.MAX_DATASET_BYTES + 4L, TagTranslationDatabase.MAX_DATASET_DOWNLOAD_BYTES)
        assertTrue(TagTranslationDatabase.MAX_SHA1_DOWNLOAD_BYTES >= 20L)
    }
}
