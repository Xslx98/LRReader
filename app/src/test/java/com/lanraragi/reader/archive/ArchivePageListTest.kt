package com.lanraragi.reader.archive

import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.server.ServerConnection
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The page grid's list load: a warm hit saves the `/files` round trip, entries
 * belong to one server, and a failure reaches the caller (which classifies it
 * for the error text) without poisoning the cache. Robolectric for
 * `android.util.LruCache`. The cache is process-wide, so each test uses its
 * own arcid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ArchivePageListTest {

    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    private fun conn(on: MockWebServer) = ServerConnection(1L, on.url("").toString().removeSuffix("/"), client)

    private fun arcId(i: Int) = i.toString(16).padStart(40, 'a')

    private fun pages(vararg names: String) =
        MockResponse().setBody(names.joinToString(",", """{"pages":[""", "]}") { "\"$it\"" })

    @Test
    fun secondLoad_isServedWithoutARequest() = runBlocking {
        val arcid = arcId(1)
        server.enqueue(pages("p1", "p2"))

        assertArrayEquals(arrayOf("p1", "p2"), ArchivePageList.load(conn(server), arcid))
        assertEquals("/api/archives/$arcid/files", server.awaitRequest().path)

        assertArrayEquals(arrayOf("p1", "p2"), ArchivePageList.load(conn(server), arcid))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sameArcidOnAnotherServer_isNotServedFromTheFirstServersEntry() = runBlocking {
        val arcid = arcId(2)
        server.enqueue(pages("from-a"))
        other.enqueue(pages("from-b", "b2"))

        ArchivePageList.load(conn(server), arcid)

        assertArrayEquals(arrayOf("from-b", "b2"), ArchivePageList.load(conn(other), arcid))
    }

    @Test
    fun failedFetch_throwsAndCachesNothing() = runBlocking {
        val arcid = arcId(3)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(pages("p1"))

        val failure = assertThrows(LRRHttpException::class.java) {
            runBlocking { ArchivePageList.load(conn(server), arcid) }
        }
        assertEquals(404, failure.code)

        assertArrayEquals(arrayOf("p1"), ArchivePageList.load(conn(server), arcid))
    }
}
