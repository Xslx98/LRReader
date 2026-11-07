package com.lanraragi.reader.cover

import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.tankoubon.TankCoverChoiceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CoverWriterTest {

    private val arcid = "a".repeat(40)
    private val member = "b".repeat(40)
    private val tankId = "TANK_1688000000"
    private val profileId = 7L

    @Volatile
    private var responseCode = 200

    /** This tank's [TankCoverCacheStamp] when the PUT reached the server. */
    @Volatile
    private var stampAtPut = -1L

    private lateinit var server: MockWebServer
    private lateinit var conn: ServerConnection
    private lateinit var eventScope: CoroutineScope

    /** A choice slot that notes this tank's [TankCoverCacheStamp] at each write. */
    private inner class RecordingStorage : TankCoverChoiceStore.Storage {
        private var value: String? = null
        var stampAtWrite = -1L

        override fun read() = value

        override fun write(value: String?) {
            stampAtWrite = TankCoverCacheStamp.get(tankId)
            this.value = value
        }
    }

    @Before
    fun setUp() {
        ArchiveCoverStamps.resetForTest(emptyMap())
        TankCoverCacheStamp.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                stampAtPut = TankCoverCacheStamp.get(tankId)
                return MockResponse().setResponseCode(responseCode).setBody("{}")
            }
        }
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        conn = ServerConnection(profileId, server.url("").toString().removeSuffix("/"), client)
        eventScope = containedTestScope()
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        server.shutdown()
    }

    /**
     * The event's collector runs inline ([containedTestScope] is unconfined),
     * so each entry is this archive's cover stamp at the moment of the event.
     */
    private fun stampsSeenByEvents(): List<Long> {
        val stamps = CopyOnWriteArrayList<Long>()
        val subscribed = CompletableDeferred<Unit>()
        eventScope.launch {
            AppEventBus.archiveCoverChangedEvent
                .onSubscription { subscribed.complete(Unit) }
                .collect { if (it.arcid == arcid) stamps.add(ArchiveCoverStamps.get(arcid)) }
        }
        runBlocking { subscribed.await() }
        return stamps
    }

    @Test
    fun `tank cover goes to the server, then the stamp, then the choice store`() = runTest {
        val storage = RecordingStorage()
        val choices = TankCoverChoiceStore(storage)
        val before = TankCoverCacheStamp.get(tankId)

        CoverWriter.setTankCover(conn, tankId, globalPage1 = 21, choices = choices) { member to 3 }

        val put = server.awaitRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/tankoubons/$tankId/thumbnail?page=21", put.path)
        assertEquals("the stamp must not move before the server has the cover", before, stampAtPut)
        assertTrue("the stamp must move before the choice is stored", storage.stampAtWrite > before)
        assertEquals(TankCoverChoiceStore.Choice(member, 3, profileId), choices.get(tankId))
    }

    @Test
    fun `tank cover on a page with no known member stamps and keeps the old choice`() = runTest {
        val choices = TankCoverChoiceStore(RecordingStorage())
        val previous = TankCoverChoiceStore.Choice(member, 1, profileId)
        choices.put(tankId, previous)
        val before = TankCoverCacheStamp.get(tankId)

        CoverWriter.setTankCover(conn, tankId, globalPage1 = 21, choices = choices) { null }

        assertTrue(TankCoverCacheStamp.get(tankId) > before)
        assertEquals(previous, choices.get(tankId))
    }

    @Test
    fun `failed tank cover write keeps the old stamp and choice`() {
        val choices = TankCoverChoiceStore(RecordingStorage())
        val previous = TankCoverChoiceStore.Choice(member, 1, profileId)
        choices.put(tankId, previous)
        val stamp = TankCoverCacheStamp.get(tankId)
        responseCode = 502

        assertThrows(LRRHttpException::class.java) {
            runBlocking {
                CoverWriter.setTankCover(conn, tankId, globalPage1 = 21, choices = choices) { member to 3 }
            }
        }

        assertEquals(stamp, TankCoverCacheStamp.get(tankId))
        assertEquals(previous, choices.get(tankId))
    }

    @Test
    fun `archive cover goes to the server, then the stamp, then the event`() = runTest {
        val stampsAtEvent = stampsSeenByEvents()

        CoverWriter.setArchiveCover(conn, arcid, page1 = 47)

        val put = server.awaitRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/archives/$arcid/thumbnail?page=47", put.path)
        assertEquals("exactly one event", 1, stampsAtEvent.size)
        assertTrue("screens rebind on the event, so the stamp must have moved", stampsAtEvent.single() > 0L)
    }

    @Test
    fun `failed archive cover write keeps the old stamp and posts no event`() {
        val stampsAtEvent = stampsSeenByEvents()
        responseCode = 502

        assertThrows(LRRHttpException::class.java) {
            runBlocking { CoverWriter.setArchiveCover(conn, arcid, page1 = 47) }
        }

        assertEquals(0L, ArchiveCoverStamps.get(arcid))
        assertEquals(emptyList<Long>(), stampsAtEvent.toList())
    }
}
