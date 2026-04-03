package com.hippo.ehviewer.client.lrr

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.hippo.ehviewer.client.lrr.LRRHttpException
import java.io.IOException
import java.util.concurrent.TimeUnit

class LRRMinionApiTest {

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

    // ── getJobStatus ───────────────────────────────────────────────

    @Test
    fun getJobStatus_success() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "state":"finished",
            "task":"plugin_exec",
            "error":"",
            "notes":"All done"
        }"""))

        val status = LRRMinionApi.getJobStatus(client, baseUrl, "123")
        assertEquals("finished", status.state)
        assertEquals("plugin_exec", status.task)
        assertEquals("", status.error)
        assertEquals("All done", status.notes)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/minion/123", req.path)
    }

    @Test
    fun getJobStatus_running() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "state":"active",
            "task":"download",
            "error":"",
            "notes":""
        }"""))

        val status = LRRMinionApi.getJobStatus(client, baseUrl, "456")
        assertEquals("active", status.state)
        assertEquals("download", status.task)
    }

    @Test
    fun getJobStatus_withError() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "state":"failed",
            "task":"plugin_exec",
            "error":"Plugin crashed",
            "notes":""
        }"""))

        val status = LRRMinionApi.getJobStatus(client, baseUrl, "789")
        assertEquals("failed", status.state)
        assertEquals("Plugin crashed", status.error)
    }

    @Test
    fun getJobStatus_serverError() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500))
        }
        try {
            LRRMinionApi.getJobStatus(client, baseUrl, "999")
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun getJobStatus_notFound() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(404))
        }
        try {
            LRRMinionApi.getJobStatus(client, baseUrl, "nonexistent")
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(404, e.code)
        }
    }

    // ── clearJobs ──────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    @Test
    fun clearJobs_success() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"clear_jobs","success":1}"""))

        LRRMinionApi.clearJobs(client, baseUrl)

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/minion/jobs", req.path)
    }

    @Suppress("DEPRECATION")
    @Test
    fun clearJobs_serverError() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500))
        }
        try {
            LRRMinionApi.clearJobs(client, baseUrl)
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    // ── Data class tests ───────────────────────────────────────────

    @Test
    fun minionJobStatus_defaults() {
        val status = LRRMinionApi.MinionJobStatus()
        assertEquals("", status.state)
        assertEquals("", status.task)
        assertEquals("", status.error)
        assertEquals("", status.notes)
    }
}
