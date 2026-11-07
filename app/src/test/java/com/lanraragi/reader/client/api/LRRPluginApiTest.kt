package com.lanraragi.reader.client.api

import com.lanraragi.reader.awaitRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** What the plugin endpoints send and how much of their answers the client decodes. */
class LRRPluginApiTest {

    private val arcid = "a".repeat(40)
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
    fun list_toleratesExtraFieldsAndEveryParametersShape() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[
                  {"name":"Array","namespace":"a","description":"d","version":"1.2","origin":"builtin",
                   "parameters":[{"type":"string","desc":"key","value":"saved"}],"oneshot_arg":"URL:","icon":"data:x"},
                  {"name":"Object","namespace":"o","version":2,"parameters":{"x":{"type":"bool"}},
                   "login_from":"loginplugin","oneshot_arg":""},
                  {"namespace":"bare","parameters":"odd","login_from":""}
                ]"""
            )
        )

        val plugins = LRRPluginApi.listMetadataPlugins(client, baseUrl).map { it.toMetadataPlugin() }

        assertEquals(listOf("a", "o", "bare"), plugins.map { it.namespace })
        assertEquals(listOf("Array", "Object", "bare"), plugins.map { it.name })
        assertEquals(listOf("1.2", "2", ""), plugins.map { it.version })
        assertEquals(listOf("URL:", null, null), plugins.map { it.oneshotLabel })
        assertEquals(listOf(null, "loginplugin", null), plugins.map { it.loginFrom })
        val request = server.awaitRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/plugins/metadata", request.path)
    }

    @Test
    fun queue_sendsTheArchivePluginAndArgument_neverAKeyParameter() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"queue_plugin_exec","success":1,"job":42}"""))

        assertEquals(42L, LRRPluginApi.queuePlugin(client, baseUrl, arcid, "copy-archive-tags", "an arg"))

        val request = server.awaitRequest()
        assertEquals("POST", request.method)
        val url = request.requestUrl!!
        assertEquals("/api/plugins/queue", url.encodedPath)
        assertEquals(setOf("id", "plugin", "arg"), url.queryParameterNames)
        assertEquals(arcid, url.queryParameter("id"))
        assertEquals("copy-archive-tags", url.queryParameter("plugin"))
        assertEquals("an arg", url.queryParameter("arg"))
    }

    @Test
    fun queue_leavesABlankArgumentOff() = runTest {
        server.enqueue(MockResponse().setBody("""{"job":1}"""))

        LRRPluginApi.queuePlugin(client, baseUrl, arcid, "p", "  ")

        assertFalse("arg" in server.awaitRequest().requestUrl!!.queryParameterNames)
    }

    /** A second POST would run the plugin, and hit its external site, twice. */
    @Test
    fun queue_isNotRetriedAfterAServerError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThrows(LRRHttpException::class.java) {
            kotlinx.coroutines.runBlocking { LRRPluginApi.queuePlugin(client, baseUrl, arcid, "p", null) }
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun queue_withoutAJobId_isAMalformedAnswer() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1}"""))

        assertThrows(LRRMissingFieldException::class.java) {
            kotlinx.coroutines.runBlocking { LRRPluginApi.queuePlugin(client, baseUrl, arcid, "p", null) }
        }
    }

    @Test
    fun job_beforeItEnds_hasNoResult() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"42","state":"active","args":["p","x"],"result":null}"""))

        val job = LRRPluginApi.getMinionJob(client, baseUrl, 42)

        assertEquals("active", job.state)
        assertNull(job.pluginResult())
        assertNull(job.failureText())
        assertEquals("/api/minion/42/detail", server.awaitRequest().path)
    }

    @Test
    fun job_finished_decodesAnErrorReportAtHttp200() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"state":"finished","result":{"type":"metadata","success":0,"error":"No match","data":{"error":"No match"}}}"""
            )
        )

        val report = LRRPluginApi.getMinionJob(client, baseUrl, 42).pluginResult()!!

        assertFalse(report.success)
        assertEquals("No match", report.error)
    }

    @Test
    fun job_finished_decodesFindings_evenANumericTitle() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"state":"finished","result":{"success":1,"error":null,
                    "data":{"new_tags":" a, b","title":1984,"summary":"  "}}}"""
            )
        )

        val report = LRRPluginApi.getMinionJob(client, baseUrl, 42).pluginResult()!!

        assertTrue(report.success)
        assertEquals(" a, b", report.data!!.newTags)
        assertEquals("1984", report.data!!.titleText)
        assertNull(report.data!!.summaryText)
    }

    @Test
    fun job_failed_keepsTheStringResult() = runTest {
        server.enqueue(MockResponse().setBody("""{"state":"failed","result":"Worker died"}"""))

        val job = LRRPluginApi.getMinionJob(client, baseUrl, 42)

        assertEquals("Worker died", job.failureText())
        assertNull(job.pluginResult())
    }
}
