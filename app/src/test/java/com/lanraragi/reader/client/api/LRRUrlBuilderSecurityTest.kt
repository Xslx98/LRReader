package com.lanraragi.reader.client.api

import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
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

/**
 * Regression tests for W0-5 (security/url-builder-no-string-concat).
 *
 * These tests guard against the vulnerability class where LRR API classes
 * were building request URLs by string-interpolating the user-supplied
 * baseUrl (`"$baseUrl/api/info"`). Such concatenation bypasses
 * [parseBaseUrl] and produces malformed URLs whenever the configured
 * server lives under a sub-path or the baseUrl is otherwise non-trivial.
 *
 * The audit's stated threat model: a hostile baseUrl such as
 * `http://attacker.invalid#@real-server/` that, depending on the URL
 * parser, can route the API key to the attacker host. Strict parsing
 * via [parseBaseUrl] makes the host unambiguous and the per-call URL
 * builder ensures the path is appended cleanly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LRRUrlBuilderSecurityTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Sub-path baseUrl: must produce /sub/api/... not /sub//api/... ──

    @Test
    fun getServerInfo_subpathBaseUrl_buildsCleanPath() = runTest {
        server.enqueue(MockResponse().setBody("""{"name":"T","version":"1"}"""))
        // Mount the "server" at /sub/ — this is the case the old string
        // concat broke: `"$baseUrl/api/info"` with a trailing slash gave
        // `/sub//api/info` (double slash).
        val baseUrl = server.url("/sub/").toString().removeSuffix("/") + "/"

        LRRServerApi.getServerInfo(client, baseUrl)

        val req = server.awaitRequest()
        assertEquals("GET", req.method)
        assertEquals(
            "Sub-path baseUrl must produce a clean /sub/api/info path",
            "/sub/api/info",
            req.path
        )
    }

    @Test
    fun getTagStats_subpathBaseUrl_buildsCleanPath() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        val baseUrl = server.url("/lrr/").toString().removeSuffix("/") + "/"

        LRRDatabaseApi.getTagStats(client, baseUrl)

        val req = server.awaitRequest()
        assertEquals("/lrr/api/database/stats", req.path)
    }

    @Test
    fun getCategories_subpathBaseUrl_buildsCleanPath() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        val baseUrl = server.url("/lrr/").toString().removeSuffix("/") + "/"

        LRRCategoryApi.getCategories(client, baseUrl)

        val req = server.awaitRequest()
        assertEquals("/lrr/api/categories", req.path)
    }

    // ── Malformed baseUrl: must throw IOException, not NPE ──

    @Test
    fun getServerInfo_malformedBaseUrl_throwsIoException() = runTest {
        try {
            LRRServerApi.getServerInfo(client, "not a valid url")
            fail("Expected IOException for malformed baseUrl")
        } catch (e: IOException) {
            assertTrue(
                "Exception message should identify the bad URL",
                e.message?.contains("Invalid server URL") == true
            )
        }
    }

    @Test
    fun getCategories_malformedBaseUrl_throwsIoException() = runTest {
        try {
            LRRCategoryApi.getCategories(client, "ftp:/no-host")
            fail("Expected IOException for malformed baseUrl")
        } catch (e: IOException) {
            // expected
        }
    }
}
