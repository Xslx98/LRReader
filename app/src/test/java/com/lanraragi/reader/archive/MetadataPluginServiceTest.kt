package com.lanraragi.reader.archive

import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.domain.MetadataPlugin
import com.lanraragi.reader.domain.PluginTagChanges
import com.lanraragi.reader.server.ServerConnection
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [MetadataPluginService] over HTTP: how a job's states and reports become an
 * outcome, and what reaches the server on apply.
 */
class MetadataPluginServiceTest {

    private val arcid = "a".repeat(40)
    private val plugin = MetadataPlugin("p", "P", "1", "", null, null)
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var server: MockWebServer
    private lateinit var conn: ServerConnection
    private lateinit var scope: CoroutineScope

    /** Answers for successive job polls; the last one repeats. */
    private var polls: List<String> = emptyList()
    private var putCode = 200

    private inner class RecordingWriter(private val raw: String?) : TagWriter {
        val after = CopyOnWriteArrayList<Pair<String, String>>()

        override suspend fun write(client: OkHttpClient, baseUrl: String, id: String, tags: String) =
            throw UnsupportedOperationException("the service writes through updateMetadata")

        override suspend fun readRaw(client: OkHttpClient, baseUrl: String, id: String): String =
            raw ?: throw IOException("read failed")

        override suspend fun afterWrite(client: OkHttpClient, baseUrl: String, id: String, oldTags: String, newTags: String) {
            after += oldTags to newTags
        }
    }

    @Before
    fun setUp() {
        scope = containedTestScope()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            private var poll = 0

            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return when {
                    request.method == "POST" -> MockResponse().setBody("""{"job":7}""")
                    request.method == "PUT" -> MockResponse().setResponseCode(putCode).setBody("{}")
                    else -> MockResponse().setBody(polls[minOf(poll++, polls.lastIndex)])
                }
            }
        }
        server.start()
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        conn = ServerConnection(3L, server.url("").toString().removeSuffix("/"), client)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun service(writer: TagWriter = RecordingWriter("")) = MetadataPluginService(writer, scope)

    private fun puts() = seen.filter { it.method == "PUT" }

    private fun finished(data: String) = """{"state":"finished","result":{"success":1,"data":$data}}"""

    @Test
    fun run_followsTheJobToItsResult_andNeverWrites() = runTest {
        polls = listOf(
            """{"state":"inactive"}""",
            """{"state":"active"}""",
            finished("""{"new_tags":" artist:foo, bare","title":"New title","summary":""}"""),
        )

        val outcome = service().run(conn, arcid, plugin, null)

        val suggestion = (outcome as MetadataPluginService.RunOutcome.Suggested).suggestion
        assertEquals(listOf("artist:foo", "bare"), suggestion.newTags)
        assertEquals("New title", suggestion.title)
        assertNull(suggestion.summary)
        assertEquals(listOf("POST", "GET", "GET", "GET"), seen.map { it.method })
        assertEquals("/api/minion/7/detail", seen[1].path)
    }

    @Test
    fun run_aPluginError_isAFailureDespiteHttp200() = runTest {
        polls = listOf("""{"state":"finished","result":{"success":0,"error":"No match","data":{"error":"No match"}}}""")

        val outcome = service().run(conn, arcid, plugin, null) as MetadataPluginService.RunOutcome.Failed

        assertEquals("No match", outcome.message)
    }

    @Test
    fun run_aDeadJob_reportsItsStringResult() = runTest {
        polls = listOf("""{"state":"failed","result":"Worker died"}""")

        val outcome = service().run(conn, arcid, plugin, null) as MetadataPluginService.RunOutcome.Failed

        assertEquals("Worker died", outcome.message)
    }

    @Test
    fun run_stopsPollingAtTheCap() = runTest {
        polls = listOf("""{"state":"active"}""")

        assertEquals(MetadataPluginService.RunOutcome.TimedOut, service().run(conn, arcid, plugin, null))

        assertEquals(120, seen.count { it.method == "GET" })
    }

    @Test
    fun apply_unionsIntoTheServersCurrentTags_andRunsTheFollowUp() = runTest {
        val writer = RecordingWriter("artist:old, rating:⭐⭐")

        service(writer).apply(conn, arcid, PluginTagChanges(tags = listOf("ARTIST:OLD", "series:new")))

        val url = puts().single().requestUrl!!
        assertEquals("/api/archives/$arcid/metadata", url.encodedPath)
        val merged = "artist:old, rating:⭐⭐, series:new"
        assertEquals(setOf("tags"), url.queryParameterNames)
        assertEquals(merged, url.queryParameter("tags"))
        assertEquals(listOf("artist:old, rating:⭐⭐" to merged), writer.after)
    }

    @Test
    fun apply_titleAndSummaryOnly_leaveTheTagsAlone() = runTest {
        val writer = RecordingWriter(raw = null)

        service(writer).apply(conn, arcid, PluginTagChanges(title = "T", summary = "S"))

        val url = puts().single().requestUrl!!
        assertEquals(setOf("title", "summary"), url.queryParameterNames)
        assertEquals("T", url.queryParameter("title"))
        assertEquals("S", url.queryParameter("summary"))
        assertTrue(writer.after.isEmpty())
    }

    @Test
    fun apply_whenTheServerAlreadyHasEverything_writesNothing() = runTest {
        val writer = RecordingWriter("a")

        service(writer).apply(conn, arcid, PluginTagChanges(tags = listOf("A")))

        assertTrue(puts().isEmpty())
        assertTrue(writer.after.isEmpty())
    }

    @Test
    fun apply_afterAFailedRead_writesNothing() {
        val writer = RecordingWriter(raw = null)

        assertThrows(IOException::class.java) {
            runBlocking { service(writer).apply(conn, arcid, PluginTagChanges(tags = listOf("a"))) }
        }

        assertTrue(puts().isEmpty())
    }

    @Test
    fun apply_locked_throwsTheStatusWithoutAFollowUp() {
        putCode = 423
        val writer = RecordingWriter("a")

        val e = assertThrows(LRRHttpException::class.java) {
            runBlocking { service(writer).apply(conn, arcid, PluginTagChanges(tags = listOf("b"))) }
        }

        assertEquals(423, e.code)
        assertTrue(writer.after.isEmpty())
    }

    /** Leaving the screen mid-write must not lose the tank follow-up of a write that lands. */
    @Test
    fun apply_outlivesItsCaller() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Pair<String, String>>()
        val writer = object : TagWriter {
            override suspend fun write(client: OkHttpClient, baseUrl: String, id: String, tags: String) =
                throw UnsupportedOperationException()

            override suspend fun readRaw(client: OkHttpClient, baseUrl: String, id: String): String {
                readStarted.complete(Unit)
                release.await()
                return "a"
            }

            override suspend fun afterWrite(client: OkHttpClient, baseUrl: String, id: String, oldTags: String, newTags: String) {
                done.complete(oldTags to newTags)
            }
        }
        val caller = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            service(writer).apply(conn, arcid, PluginTagChanges(tags = listOf("b")))
            fail("the caller was cancelled")
        }

        withTimeout(10_000) { readStarted.await() }
        caller.cancel()
        caller.join()
        release.complete(Unit)

        assertEquals("a" to "a, b", withTimeout(10_000) { done.await() })
        assertEquals("a, b", server.awaitRequest().requestUrl!!.queryParameter("tags"))
    }

    @Test
    fun apply_nothingConfirmed_touchesNothing() = runTest {
        service(RecordingWriter(raw = null)).apply(conn, arcid, PluginTagChanges())

        assertTrue(seen.isEmpty())
    }
}
