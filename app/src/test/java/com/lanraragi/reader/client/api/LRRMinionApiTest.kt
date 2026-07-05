package com.lanraragi.reader.client.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
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
    fun getJobStatus_finished_withObjectNotes() = runTest {
        // Spec example: notes is an arbitrary JSON object whose payload is
        // task-specific. Verify we deserialise it as JsonElement rather
        // than throwing on the non-string value.
        server.enqueue(MockResponse().setBody("""{
            "state":"finished",
            "task":"handle_upload",
            "notes":{"example_note":"This note could contain the name of the upload, for example."},
            "error":null
        }"""))

        val status = LRRMinionApi.getJobStatus(client, baseUrl, 123L)
        assertEquals("finished", status.state)
        assertEquals("handle_upload", status.task)
        assertNull(status.error)
        assertNotNull("notes object should deserialise to JsonElement", status.notes)
        val notesObj = status.notes!!.jsonObject
        assertTrue(notesObj.containsKey("example_note"))
        assertEquals(
            "This note could contain the name of the upload, for example.",
            notesObj["example_note"]!!.jsonPrimitive.content,
        )

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/minion/123", req.path)
    }

    @Test
    fun getJobStatus_active_nullNotesAndError() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "state":"active",
            "task":"download_url",
            "notes":null,
            "error":null
        }"""))

        val status = LRRMinionApi.getJobStatus(client, baseUrl, 456L)
        assertEquals("active", status.state)
        assertEquals("download_url", status.task)
        assertNull(status.notes)
        assertNull(status.error)
    }

    @Test
    fun getJobStatus_failed_withErrorString() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "state":"failed",
            "task":"plugin_exec",
            "notes":null,
            "error":"Plugin crashed"
        }"""))

        val status = LRRMinionApi.getJobStatus(client, baseUrl, 789L)
        assertEquals("failed", status.state)
        assertEquals("Plugin crashed", status.error)
    }

    @Test
    fun getJobStatus_serverError_propagatesHttpException() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500))
        }
        try {
            LRRMinionApi.getJobStatus(client, baseUrl, 999L)
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun getJobStatus_pathUsesIntegerJobId() = runTest {
        server.enqueue(MockResponse().setBody("""{"state":"inactive","task":"x"}"""))

        LRRMinionApi.getJobStatus(client, baseUrl, 4_200_000_000L)

        val req = server.takeRequest()
        assertEquals("/api/minion/4200000000", req.path)
    }

    // ── Data class defaults ────────────────────────────────────────

    @Test
    fun minionJobStatus_defaults() {
        val status = LRRMinionApi.MinionJobStatus()
        assertEquals("", status.state)
        assertEquals("", status.task)
        assertNull(status.error)
        assertNull(status.notes)
    }

    @Test
    fun minionJobStatus_canHoldJsonObjectNotes() {
        // Sanity check: the notes field accepts a JsonObject so callers
        // can pattern-match via .jsonObject without a custom deserialiser.
        val notes: JsonObject = kotlinx.serialization.json.buildJsonObject {
            put("k", kotlinx.serialization.json.JsonPrimitive("v"))
        }
        val status = LRRMinionApi.MinionJobStatus(state = "finished", task = "x", notes = notes)
        assertEquals("v", status.notes!!.jsonObject["k"]!!.jsonPrimitive.content)
    }
}
