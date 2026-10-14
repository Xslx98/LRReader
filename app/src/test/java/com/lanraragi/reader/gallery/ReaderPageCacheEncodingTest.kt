package com.lanraragi.reader.gallery

import com.lanraragi.reader.awaitRequest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The reader's page fetch asks for the identity encoding, like the download
 * worker, so a gzipping proxy cannot strip Content-Length and silently turn
 * off the completeness check and the progress bar (audit 2026-10-13 PERF-08).
 */
class ReaderPageCacheEncodingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun pageRequest_asksForIdentityEncoding() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }.also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte()
        }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        ReaderPageCache.downloadToFile(client, server.url("/page").toString(), tmp.newFile("page_0"))

        assertEquals("identity", server.awaitRequest().getHeader("Accept-Encoding"))
    }
}
