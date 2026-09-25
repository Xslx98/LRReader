package com.lanraragi.reader.tankoubon

import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.TankTagSyncFailedEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TankTagSyncer] wire contract (spec 2026-09-22 §5.2–§5.3): every rule
 * reads `/full`, PUTs the WHOLE tag string as `metadata.tags` only when it
 * changed, never touches `archives`, and a failed PUT posts the failure
 * event instead of throwing.
 */
class TankTagSyncerTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var client: OkHttpClient

    @Volatile
    private var tankTags: String? = "artist:a, hand:written, rating:⭐⭐"

    @Volatile
    private var members: List<Pair<String, String>> = listOf(ID_A to "artist:a, date_added:1", ID_B to "artist:a, parody:p")

    @Volatile
    private var putStatus = 200

    private val putBodies = CopyOnWriteArrayList<String>()
    // Recorded on the MockWebServer thread, asserted on the test thread:
    // an assertion inside the dispatcher only surfaces as a failed PUT.
    @Volatile
    private var membershipTouched = false
    private val failures = CopyOnWriteArrayList<TankTagSyncFailedEvent>()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "PUT" && path == "/api/tankoubons/$TANK" -> {
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        if (body.containsKey("archives")) membershipTouched = true
                        putBodies.add(body.getValue("metadata").jsonObject.getValue("tags").jsonPrimitive.content)
                        MockResponse().setResponseCode(putStatus).setBody("""{"success":1}""")
                    }
                    path.startsWith("/api/tankoubons/$TANK/full") -> MockResponse().setBody(fullJson())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
        // Observe the real event bus (subscribed synchronously: Unconfined).
        eventScope.launch { AppEventBus.tankTagSyncFailedEvent.collect { failures.add(it) } }
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        server.shutdown()
        assertFalse("a tag sync PUT must never carry the membership list", membershipTouched)
    }

    private fun fullJson(): String {
        val data = members.joinToString(",") { (id, tags) ->
            """{"arcid":"$id","title":"t","tags":"$tags","pagecount":3,"progress":0,"lastreadtime":0,""" +
                """"isnew":"false","extension":"zip","filename":"$id.zip","summary":""}"""
        }
        val ids = members.joinToString(",") { "\"${it.first}\"" }
        val tags = tankTags?.let { "\"$it\"" } ?: "null"
        return """{"result":{"id":"$TANK","name":"Tank","summary":null,"tags":$tags,"progress":0,""" +
            """"archives":[$ids],"full_data":[$data]},"total":1,"filtered":1}"""
    }

    @Test
    fun afterAdd_putsTankUnionMembersWholeString() = runTest {
        assertTrue(TankTagSyncer.afterAdd(client, baseUrl, TANK))

        assertEquals("artist:a, hand:written, rating:⭐⭐, parody:p", putBodies.single())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun afterAdd_noPutWhenNothingChanges() = runTest {
        tankTags = "artist:a, parody:p"

        assertFalse(TankTagSyncer.afterAdd(client, baseUrl, TANK))

        assertTrue(putBodies.isEmpty())
    }

    @Test
    fun afterRemove_usesThePreRemovalSnapshotForRule3() = runTest {
        tankTags = "artist:a, parody:p, hand:written, rating:⭐⭐"
        val before = TankTagSyncer.snapshot(client, baseUrl, TANK)!!
        // The server already dropped B; the snapshot still knows B's tags.
        members = listOf(ID_A to "artist:a, date_added:1")

        assertTrue(TankTagSyncer.afterRemove(client, baseUrl, before, listOf(ID_B)))

        assertEquals("artist:a, hand:written, rating:⭐⭐", putBodies.single())
    }

    @Test
    fun afterMemberTagsChanged_swapsThatMembersContribution() = runTest {
        tankTags = "artist:a, parody:p, hand:written"
        // Server already holds B's NEW tags.
        members = listOf(ID_A to "artist:a", ID_B to "artist:a, new:n")

        assertTrue(TankTagSyncer.afterMemberTagsChanged(client, baseUrl, TANK, ID_B, "artist:a, parody:p", "artist:a, new:n"))

        assertEquals("artist:a, hand:written, new:n", putBodies.single())
    }

    @Test
    fun afterMemberTagsChanged_ignoresArchivesNotInTheTank() = runTest {
        assertFalse(TankTagSyncer.afterMemberTagsChanged(client, baseUrl, TANK, "c".repeat(40), "x:1", "x:2"))
        assertTrue(putBodies.isEmpty())
    }

    @Test
    fun reset_keepsOnlyExcludedTankTagsPlusTheUnion() = runTest {
        assertTrue(TankTagSyncer.reset(client, baseUrl, TANK))

        assertEquals("rating:⭐⭐, artist:a, parody:p", putBodies.single())
    }

    @Test
    fun autoFill_writesTheUnionOnlyForEmptyTaggedTanksWithMembers() = runTest {
        tankTags = "rating:⭐⭐"
        val full = com.lanraragi.reader.client.api.LRRTankoubonApi.getTankoubonFull(client, baseUrl, TANK).result

        assertEquals("rating:⭐⭐, artist:a, parody:p", TankTagSyncer.autoFillIfNeeded(client, baseUrl, full))
        assertEquals(1, putBodies.size)

        tankTags = "artist:a"
        val filled = com.lanraragi.reader.client.api.LRRTankoubonApi.getTankoubonFull(client, baseUrl, TANK).result
        assertNull(TankTagSyncer.autoFillIfNeeded(client, baseUrl, filled))
        assertEquals(1, putBodies.size)
    }

    @Test
    fun failedPut_postsTheEventAndReturnsFalse() = runTest {
        putStatus = 500

        assertFalse(TankTagSyncer.afterAdd(client, baseUrl, TANK))

        assertEquals(listOf(TankTagSyncFailedEvent(TANK, "Tank")), failures)
    }

    @Test
    fun unreachableFetch_isSilentlyFalse() = runTest {
        server.shutdown()

        assertFalse(TankTagSyncer.afterAdd(client, baseUrl, TANK))
        assertNull(TankTagSyncer.snapshot(client, baseUrl, TANK))
        assertTrue("nothing was written, nothing to report", failures.isEmpty())
    }

    private companion object {
        const val TANK = "TANK_1700000000"
        val ID_A = "a".repeat(40)
        val ID_B = "b".repeat(40)
    }
}
