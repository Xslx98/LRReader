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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TankCategorySyncer] wire contract (spec 2026-09-22 §6): reads
 * `/api/categories`, then one PUT / DELETE per computed change on the
 * TANK id; write failures post the CATEGORIES event, a failed fetch is
 * silent.
 */
class TankCategorySyncerTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var client: OkHttpClient

    /** id → (members, search) served by /api/categories. */
    @Volatile
    private var categories: List<Triple<String, List<String>, String>> = emptyList()

    @Volatile
    private var writeStatus = 200

    private val writes = CopyOnWriteArrayList<String>()
    private val failures = CopyOnWriteArrayList<TankTagSyncFailedEvent>()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path == "/api/categories" -> MockResponse().setBody(categoriesJson())
                    path.startsWith("/api/categories/") -> {
                        writes.add("${request.method} $path")
                        MockResponse().setResponseCode(writeStatus).setBody("""{"success":1}""")
                    }
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
    }

    private fun categoriesJson(): String = categories.joinToString(",", "[", "]") { (id, members, search) ->
        """{"id":"$id","name":"$id","archives":[${members.joinToString(",") { "\"$it\"" }}],"pinned":"0","search":"$search"}"""
    }

    @Test
    fun afterAdd_putsTheTankIntoTheNewMembersStaticCategories() = runTest {
        categories = listOf(
            Triple("SET_0000000001", listOf(A), ""),
            Triple("SET_0000000002", listOf(A, TANK), ""),
            Triple("SET_0000000dyn", listOf(A), "artist:x"),
        )

        assertTrue(TankCategorySyncer.afterAdd(client, baseUrl, TANK, "Tank", listOf(A)))

        assertEquals(listOf("PUT /api/categories/SET_0000000001/$TANK"), writes)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun afterRemove_deletesTheTankFromCategoriesNoRemainingMemberExplains() = runTest {
        categories = listOf(
            Triple("SET_00000manua", listOf(TANK), ""),
            Triple("SET_000000000a", listOf(A, TANK), ""),
            Triple("SET_0000shared", listOf(A, M, TANK), ""),
        )

        assertTrue(TankCategorySyncer.afterRemove(client, baseUrl, TANK, "Tank", listOf(A, M), listOf(A)))

        assertEquals(listOf("DELETE /api/categories/SET_000000000a/$TANK"), writes)
    }

    @Test
    fun onDissolve_deletesEveryStaticMembership() = runTest {
        categories = listOf(Triple("SET_0000000001", listOf(TANK), ""), Triple("SET_0000000002", listOf(A), ""), Triple("SET_0000000003", listOf(TANK, A), ""))

        assertTrue(TankCategorySyncer.onDissolve(client, baseUrl, TANK, "Tank"))

        assertEquals(listOf("DELETE /api/categories/SET_0000000001/$TANK", "DELETE /api/categories/SET_0000000003/$TANK"), writes)
    }

    @Test
    fun reset_addsMissingAndRemovesManual() = runTest {
        categories = listOf(Triple("SET_0000000add", listOf(A), ""), Triple("SET_000000drop", listOf(TANK), ""), Triple("SET_000000keep", listOf(A, TANK), ""))

        assertTrue(TankCategorySyncer.reset(client, baseUrl, TANK, "Tank", listOf(A)))

        assertEquals(listOf("PUT /api/categories/SET_0000000add/$TANK", "DELETE /api/categories/SET_000000drop/$TANK"), writes)
    }

    @Test
    fun noChanges_isTrueWithoutWrites() = runTest {
        categories = listOf(Triple("SET_0000000001", listOf(A, TANK), ""))

        assertTrue(TankCategorySyncer.afterAdd(client, baseUrl, TANK, "Tank", listOf(A)))

        assertTrue(writes.isEmpty())
    }

    @Test
    fun failedWrite_postsCategoriesEventAndContinues() = runTest {
        categories = listOf(Triple("SET_0000000001", listOf(A), ""), Triple("SET_0000000002", listOf(A), ""))
        writeStatus = 500

        assertFalse(TankCategorySyncer.afterAdd(client, baseUrl, TANK, "Tank", listOf(A)))

        assertEquals(2, writes.size)
        assertEquals(TankTagSyncFailedEvent(TANK, "Tank", TankTagSyncFailedEvent.Kind.CATEGORIES), failures.first())
    }

    @Test
    fun unreachableFetch_isSilentlyFalse() = runTest {
        server.shutdown()

        assertFalse(TankCategorySyncer.afterAdd(client, baseUrl, TANK, "Tank", listOf(A)))
        assertTrue(failures.isEmpty())
    }

    private companion object {
        const val TANK = "TANK_1700000000"
        val A = "a".repeat(40)
        val M = "m".repeat(40)
    }
}
