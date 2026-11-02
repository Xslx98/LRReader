package com.lanraragi.reader.archive

import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.ArchiveDeletedEvent
import com.lanraragi.reader.server.ServerConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ArchiveDeletion]: the DELETE goes to the archive's source profile's
 * server, and only a successful delete posts [ArchiveDeletedEvent].
 */
class ArchiveDeletionTest {

    private val arcid = "c".repeat(40)

    private lateinit var server: MockWebServer
    private lateinit var conn: ServerConnection
    private val asked = CopyOnWriteArrayList<Long>()
    private val events = CopyOnWriteArrayList<ArchiveDeletedEvent>()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var deletion: ArchiveDeletion

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
        conn = ServerConnection(5L, server.url("").toString().removeSuffix("/"), client)
        deletion = ArchiveDeletion(serviceScope) { asked += it; conn }
        eventScope.launch { AppEventBus.archiveDeletedEvent.collect { events.add(it) } }
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        serviceScope.cancel()
        server.shutdown()
    }

    @Test
    fun deleteAsync_deletesOnTheSourceServer_thenPostsTheEvent() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"delete_archive","success":1,"id":"$arcid"}"""))
        val result = CompletableDeferred<Result<Unit>>()

        deletion.deleteAsync(5L, arcid) { result.complete(it) }

        assertTrue(result.await().isSuccess)
        assertEquals(listOf(5L), asked)
        val req = server.awaitRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/archives/$arcid", req.path)
        assertEquals(listOf(ArchiveDeletedEvent(arcid)), events)
    }

    @Test
    fun deleteAsync_failure_reportsItAndPostsNoEvent() = runTest {
        server.enqueue(MockResponse().setResponseCode(423))
        val result = CompletableDeferred<Result<Unit>>()

        deletion.deleteAsync(5L, arcid) { result.complete(it) }

        val error = result.await().exceptionOrNull()
        assertTrue(error.toString(), error is LRRHttpException && error.code == 423)
        assertTrue(events.isEmpty())
    }
}
