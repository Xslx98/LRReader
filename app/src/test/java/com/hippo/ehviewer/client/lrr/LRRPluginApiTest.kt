package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

import java.io.IOException
import java.util.concurrent.TimeUnit

class LRRPluginApiTest {

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

    // ── getPlugins ─────────────────────────────────────────────────

    @Test
    fun getPlugins_success() = runTest {
        server.enqueue(MockResponse().setBody("""[
            {
                "name":"E-Hentai Metadata",
                "namespace":"ehentai",
                "type":"metadata",
                "description":"Fetch meta from EH",
                "icon":"🔍",
                "oneshot_arg":"",
                "parameters":[
                    {"type":"string","desc":"API key"}
                ]
            },
            {
                "name":"nHentai Downloader",
                "namespace":"nhentai",
                "type":"download",
                "description":"Download from nH",
                "icon":"📥",
                "oneshot_arg":"URL",
                "parameters":[]
            }
        ]"""))

        val plugins = LRRPluginApi.getPlugins(client, baseUrl, "metadata")
        assertEquals(2, plugins.size)

        val first = plugins[0]
        assertEquals("E-Hentai Metadata", first.name)
        assertEquals("ehentai", first.namespace)
        assertEquals("metadata", first.type)
        assertEquals("Fetch meta from EH", first.description)
        assertEquals("🔍", first.icon)
        assertEquals(1, first.parameters.size)
        assertEquals("string", first.parameters[0].type)
        assertEquals("API key", first.parameters[0].desc)

        val second = plugins[1]
        assertEquals("nhentai", second.namespace)
        assertEquals("URL", second.oneshot_arg)
        assertTrue(second.parameters.isEmpty())

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/plugins/metadata", req.path)
    }

    @Test
    fun getPlugins_emptyList() = runTest {
        server.enqueue(MockResponse().setBody("[]"))

        val plugins = LRRPluginApi.getPlugins(client, baseUrl, "script")
        assertTrue(plugins.isEmpty())

        val req = server.takeRequest()
        assertEquals("/api/plugins/script", req.path)
    }

    @Test
    fun getPlugins_differentTypes() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        LRRPluginApi.getPlugins(client, baseUrl, "download")
        assertEquals("/api/plugins/download", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("[]"))
        LRRPluginApi.getPlugins(client, baseUrl, "login")
        assertEquals("/api/plugins/login", server.takeRequest().path)
    }

    @Test
    fun getPlugins_serverError() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500))
        }
        try {
            LRRPluginApi.getPlugins(client, baseUrl, "metadata")
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    // ── runPlugin ──────────────────────────────────────────────────

    @Test
    fun runPlugin_success_objectData() = runTest {
        // Per OpenAPI v0.9.6 spec, the success response shape is
        // { operation, type, success, error, data: object }
        server.enqueue(MockResponse().setBody("""{
            "operation":"use_plugin",
            "type":"metadata",
            "success":1,
            "data":{"new_tags":"pages:45"}
        }"""))

        val result = LRRPluginApi.runPlugin(
            client, baseUrl,
            namespace = "ehentai",
            archiveId = "abc123",
            arg = "extra_arg"
        )
        assertEquals("use_plugin", result.operation)
        assertEquals("metadata", result.type)
        assertEquals(1, result.success)
        assertNull(result.error)
        // data is a JsonElement object — pluck the plugin-specific field
        val dataObj = result.data!!.jsonObject
        assertEquals("pages:45", dataObj["new_tags"]!!.jsonPrimitive.content)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.contains("/api/plugins/use"))
        assertTrue(req.path!!.contains("plugin=ehentai"))
        assertTrue(req.path!!.contains("id=abc123"))
        assertTrue(req.path!!.contains("arg=extra_arg"))
    }

    @Test
    fun runPlugin_failure_nullDataAndErrorString() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "operation":"use_plugin",
            "type":null,
            "success":0,
            "error":"Plugin crashed",
            "data":null
        }"""))

        val result = LRRPluginApi.runPlugin(client, baseUrl, namespace = "broken")
        assertEquals(0, result.success)
        assertEquals("Plugin crashed", result.error)
        assertNull(result.type)
        assertNull(result.data)
    }

    @Test
    fun runPlugin_withoutOptionalParams() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "operation":"use_plugin","success":1,"data":{}
        }"""))

        LRRPluginApi.runPlugin(client, baseUrl, namespace = "chaika")

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("plugin=chaika"))
        assertFalse(req.path!!.contains("id="))
        assertFalse(req.path!!.contains("arg="))
    }

    @Test
    fun runPlugin_failure() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500))
        }
        try {
            LRRPluginApi.runPlugin(client, baseUrl, namespace = "bad_plugin")
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    // ── Data class tests ───────────────────────────────────────────

    @Test
    fun pluginInfo_defaults() {
        val info = LRRPluginApi.PluginInfo()
        assertEquals("", info.name)
        assertEquals("", info.namespace)
        assertEquals("", info.type)
        assertEquals("", info.description)
        assertEquals("", info.icon)
        assertEquals("", info.oneshot_arg)
        assertTrue(info.parameters.isEmpty())
    }

    @Test
    fun pluginParameter_defaults() {
        val param = LRRPluginApi.PluginParameter()
        assertEquals("", param.type)
        assertEquals("", param.desc)
    }

    @Test
    fun pluginRunResult_defaults() {
        val result = LRRPluginApi.PluginRunResult()
        assertEquals("", result.operation)
        assertNull(result.type)
        assertEquals(0, result.success)
        assertNull(result.error)
        assertNull(result.data)
    }
}
