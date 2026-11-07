package com.lanraragi.reader.server

import com.lanraragi.reader.awaitRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The reading-stats page is offline by design: [excludedNamespaces] may touch
 * the network only for a server whose exclusions were never observed, and a
 * failure there must degrade to the built-in set, not an error.
 */
class ServerExcludedNamespacesTest {

    private lateinit var server: MockWebServer
    private lateinit var conn: ServerConnection

    @Before
    fun setUp() {
        ServerCapabilityCache.attachStore(null)
        ServerCapabilityCache.clear()
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        conn = ServerConnection(1L, server.url("").toString().removeSuffix("/"), client)
    }

    @After
    fun tearDown() {
        server.shutdown()
        ServerCapabilityCache.clear()
    }

    @Test
    fun observedServer_isAnsweredWithoutARequest() = runTest {
        ServerCapabilityCache.setExcludedNamespaces(conn.baseUrl, listOf("source"))

        assertEquals(setOf("source"), excludedNamespaces(conn))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun observedEmptySet_isNotRefetched() = runTest {
        ServerCapabilityCache.setExcludedNamespaces(conn.baseUrl, emptyList())

        assertEquals(emptySet<String>(), excludedNamespaces(conn))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unobservedServer_fetchesOnce_thenStaysOffline() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"name":"s","version":"0.9.21","excluded_namespaces":["date_added","source"]}""",
            ),
        )

        assertEquals(setOf("date_added", "source"), excludedNamespaces(conn))
        assertEquals("/api/info", server.awaitRequest().path)

        assertEquals(setOf("date_added", "source"), excludedNamespaces(conn))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fetchFailure_degradesToTheEmptySet() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(emptySet<String>(), excludedNamespaces(conn))
        assertEquals(1, server.requestCount)
    }
}
