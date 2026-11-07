package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.domain.Tankoubon
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.TankMembershipChangedEvent
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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TankMembershipService] rules (spec 2026-09-22 §5–§6): tanks being left
 * are snapshotted BEFORE any removal, membership calls go out sequentially
 * in list order and stop at the first failure (a locked tank), a tank that
 * was empty gets its cover seeded, and a completed change invalidates the
 * tank list, bumps Recent and posts one membership event.
 */
class TankMembershipServiceTest {

    private val arcid = "a".repeat(40)
    private val other = "b".repeat(40)

    private lateinit var server: MockWebServer
    private lateinit var conn: ServerConnection
    private lateinit var service: TankMembershipService
    private val requests = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<TankMembershipChangedEvent>()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recent = TankRecentStore(
        object : TankRecentStore.Storage {
            private var value: String? = null
            override fun read(): String? = value
            override fun write(value: String?) {
                this.value = value
            }
        }
    )

    /** Tank ids whose membership writes answer 423 Locked. */
    @Volatile
    private var lockedTanks: Set<String> = emptySet()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests.add("${request.method} $path")
                val tankId = path.removePrefix("/api/tankoubons/").substringBefore('/')
                return when {
                    request.method == "GET" && path == "/api/categories" -> MockResponse().setBody("[]")
                    request.method == "GET" && (path == "/api/tankoubons" || path.startsWith("/api/tankoubons?")) ->
                        MockResponse().setBody("""{"result":[],"total":0,"filtered":0}""")
                    request.method == "GET" && path.endsWith("/full?page=-1") -> MockResponse().setBody(fullJson(tankId))
                    path.endsWith("/$arcid") && tankId in lockedTanks -> MockResponse().setResponseCode(423)
                    else -> MockResponse().setBody("""{"success":1}""")
                }
            }
        }
        server.start()
        val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
        conn = ServerConnection(7L, server.url("").toString().removeSuffix("/"), client)
        service = TankMembershipService(serviceScope, recent)
        eventScope.launch { AppEventBus.tankMembershipChangedEvent.collect { events.add(it) } }
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        serviceScope.cancel()
        server.shutdown()
    }

    private fun fullJson(tankId: String): String =
        """{"result":{"id":"$tankId","name":"$tankId","summary":null,"tags":"","progress":0,""" +
            """"archives":["$other"],"full_data":[{"arcid":"$other","title":"t","tags":"artist:x","pagecount":1,""" +
            """"progress":0,"lastreadtime":0,"isnew":"false","extension":"zip","filename":"b.zip","summary":""}]},""" +
            """"total":1,"filtered":1}"""

    private fun tank(id: String, vararg members: String) =
        Tankoubon(id = id, name = "name-$id", archives = members.toList())

    @Test
    fun apply_snapshotsLeftTanksFirst_writesSequentially_seedsEmptyCover() = runTest {
        val tanks = listOf(tank("TANK_1700000001"), tank("TANK_1700000002", arcid), tank("TANK_1700000003", other), tank("TANK_1700000004", arcid))
        TankListCache.put(conn.baseUrl, tanks)

        val newIds = service.apply(
            conn, arcid, tanks,
            originalIds = setOf("TANK_1700000002", "TANK_1700000004"),
            checkedIds = setOf("TANK_1700000001", "TANK_1700000003", "TANK_1700000004"),
        )

        assertEquals(listOf("TANK_1700000001", "TANK_1700000003", "TANK_1700000004"), newIds)
        val membership = requests.filter {
            it.endsWith("/$arcid") || it.contains("/thumbnail") || it.endsWith("TANK_1700000002/full?page=-1")
        }
        assertEquals(
            listOf(
                "GET /api/tankoubons/TANK_1700000002/full?page=-1", // snapshot before any write
                "PUT /api/tankoubons/TANK_1700000001/$arcid",
                "PUT /api/tankoubons/TANK_1700000001/thumbnail?page=1", // TANK_1700000001 was empty
                "DELETE /api/tankoubons/TANK_1700000002/$arcid",
                "PUT /api/tankoubons/TANK_1700000003/$arcid", // not empty: no cover seed
            ),
            membership,
        )
        assertEquals(listOf("TANK_1700000001", "TANK_1700000003"), recent.get(7L)) // first joined on top
        assertEquals(
            listOf(
                TankMembershipChangedEvent(
                    arcid,
                    joined = listOf(
                        TankMembershipChangedEvent.JoinedTank("TANK_1700000001", "name-TANK_1700000001", wasEmpty = true),
                        TankMembershipChangedEvent.JoinedTank("TANK_1700000003", "name-TANK_1700000003", wasEmpty = false),
                    ),
                    left = listOf("TANK_1700000002"),
                )
            ),
            events,
        )
        // The cached list was dropped: the next read goes back to the server.
        requests.clear()
        TankListCache.get(conn.client, conn.baseUrl)
        assertTrue(requests.toString(), requests.any { it.startsWith("GET /api/tankoubons") && !it.contains("/full") })
    }

    @Test
    fun apply_noChange_postsNoEvent() = runTest {
        val tanks = listOf(tank("TANK_1700000001", arcid))

        val newIds = service.apply(conn, arcid, tanks, originalIds = setOf("TANK_1700000001"), checkedIds = setOf("TANK_1700000001"))

        assertEquals(listOf("TANK_1700000001"), newIds)
        assertTrue(requests.none { it.endsWith("/$arcid") })
        assertTrue(events.isEmpty())
    }

    @Test
    fun applyAsync_lockedTank_failsFastWithoutLaterWritesOrEvent() = runTest {
        lockedTanks = setOf("TANK_1700000001")
        val tanks = listOf(tank("TANK_1700000001", other), tank("TANK_1700000002", other))
        val result = CompletableDeferred<Result<List<String>>>()

        service.applyAsync(conn, arcid, tanks, emptySet(), setOf("TANK_1700000001", "TANK_1700000002")) { result.complete(it) }

        val error = result.await().exceptionOrNull()
        assertTrue(error.toString(), error is LRRHttpException && error.code == 423)
        assertEquals(listOf("PUT /api/tankoubons/TANK_1700000001/$arcid"), requests.filter { it.endsWith("/$arcid") })
        assertTrue(recent.get(7L).isEmpty())
        assertTrue(events.isEmpty())
    }
}
