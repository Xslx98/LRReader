package com.lanraragi.reader.ui.scene.gallery.list

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.client.TankCoverCacheStamp
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.download.TankMembershipSync
import com.lanraragi.reader.ui.TankMembershipSyncFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * [TankListService] against a MockWebServer LANraragi: the full list load and
 * its side effects (cover stamps, membership sync), the missing-cover probe,
 * auto-sort / restore, the member fetch and create. Ported from the retired
 * TankoubonsViewModelTest when the drawer's tank list moved into the gallery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TankListServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var background: CoroutineScope
    private lateinit var service: TankListService
    private val syncCalls = CopyOnWriteArrayList<Triple<Long, String, List<TankMembershipSync.TankTruth>>>()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Context>()
        server = MockWebServer()
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        background = CoroutineScope(Dispatchers.Default + SupervisorJob())
        service = TankListService(background, client = { client })
        service.membershipSync = TankMembershipSyncFactory.Runner { p, u, t -> syncCalls.add(Triple(p, u, t)) }
    }

    @After
    fun tearDown() {
        background.cancel()
        server.shutdown()
    }

    /** Valid 40-char hex arcid for member [i] (cover fallbacks re-validate ids). */
    private fun arcId(i: Int) = i.toString(16).padStart(40, '0')

    private fun tankJson(id: String, name: String, archiveCount: Int = 0, progress: Int = 0): String {
        val archives = (1..archiveCount).joinToString(",") { "\"${arcId(it)}\"" }
        return """{"id":"$id","name":"$name","archives":[$archives],""" +
            """"summary":null,"tags":null,"progress":$progress}"""
    }

    private fun pageJson(total: Int, vararg tanks: String): String =
        """{"result":[${tanks.joinToString(",")}],"total":$total,"filtered":$total}"""

    private fun fullJson(id: String, vararg members: Pair<String, String>): String {
        val data = members.joinToString(",") { (arcid, title) ->
            """{"arcid":"$arcid","title":"$title","tags":"","pagecount":3}"""
        }
        val ids = members.joinToString(",") { "\"${it.first}\"" }
        return """{"result":{"id":"$id","name":"Alpha","archives":[$ids],"full_data":[$data],""" +
            """"summary":null,"tags":null,"progress":0},"total":1,"filtered":1}"""
    }

    /** List endpoint serves [listBody]; cover probes answer [probeCode]. */
    private class ProbeDispatcher(@Volatile var listBody: String, private val probeCode: Int) : Dispatcher() {
        val probePaths = CopyOnWriteArrayList<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            if ("thumbnail" !in path) return MockResponse().setBody(listBody)
            probePaths += path
            // HEAD (the probe) must not carry a body, or it corrupts the kept-alive connection.
            return if (request.method == "HEAD") {
                MockResponse().setResponseCode(probeCode)
            } else {
                MockResponse().setResponseCode(probeCode)
                    .setBody(if (probeCode == 202) """{"success":1,"job":1}""" else "img")
            }
        }
    }

    // ── loadAll ────────────────────────────────────────────────────

    @Test
    fun loadAll_rekeysOnlyTanksWhoseMembersChanged() = runBlocking {
        val a = "TANK_0000000101"
        val b = "TANK_0000000102"
        server.dispatcher = ProbeDispatcher(
            pageJson(2, tankJson(a, "Alpha", archiveCount = 1), tankJson(b, "Beta", archiveCount = 1)), 200,
        )
        service.loadAll(baseUrl, 1L)
        val stampA = TankCoverCacheStamp.get(a)
        val stampB = TankCoverCacheStamp.get(b)

        (server.dispatcher as ProbeDispatcher).listBody =
            pageJson(2, tankJson(a, "Alpha", archiveCount = 2), tankJson(b, "Beta", archiveCount = 1))
        service.loadAll(baseUrl, 1L)

        assertTrue(TankCoverCacheStamp.get(a) > stampA)
        assertEquals(stampB, TankCoverCacheStamp.get(b))
    }

    @Test
    fun loadAll_failure_leavesStampsUntouched_andNeverSyncs() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val before = TankCoverCacheStamp.generation

        try {
            service.loadAll(baseUrl, 1L)
            fail("a 500 must propagate")
        } catch (expected: Exception) {
            // ok
        }

        assertEquals(before, TankCoverCacheStamp.generation)
        assertTrue(syncCalls.isEmpty())
    }

    @Test
    fun loadAll_success_handsServerMembershipToSyncOnce() = runBlocking {
        server.enqueue(MockResponse().setBody(pageJson(1, tankJson("TANK_0000000103", "Alpha", 2, progress = 5))))

        service.loadAll(baseUrl, 7L)

        awaitUntil { syncCalls.size == 1 }
        val (profileId, url, truth) = syncCalls.single()
        assertEquals(7L, profileId)
        assertEquals(baseUrl, url)
        val tank = truth.single()
        assertEquals("TANK_0000000103", tank.tankId)
        assertEquals(listOf(arcId(1), arcId(2)), tank.memberIds)
        assertEquals(5, tank.progress)
    }

    @Test
    fun loadAll_multiPage_isZeroBasedAndAccumulatesUntilTotal() = runBlocking {
        // LANraragi paginates /api/tankoubons 0-BASED: the first page is the
        // parameterless request and the SECOND page is ?page=1 (smoke 2026-07-05).
        server.enqueue(MockResponse().setBody(pageJson(2, tankJson("TANK_0000000104", "First"))))
        server.enqueue(MockResponse().setBody(pageJson(2, tankJson("TANK_0000000105", "Second"))))

        val tanks = service.loadAll(baseUrl, 1L)

        assertEquals(listOf("First", "Second"), tanks.map { it.name })
        assertEquals("/api/tankoubons", server.awaitRequest().path)
        assertEquals("/api/tankoubons?page=1", server.awaitRequest().path)
    }

    // ── cover probe ────────────────────────────────────────────────
    //
    // The plain thumbnail route serves a placeholder when no cover was ever
    // generated — indistinguishable client-side — so member-having tanks are
    // probed with ?no_fallback=true: 202 = missing → first member's cover.

    @Test
    fun probe202_mapsTankToFirstMemberFallback() = runBlocking {
        val dispatcher = ProbeDispatcher(pageJson(1, tankJson("TANK_0000000106", "Alpha", 2)), 202)
        server.dispatcher = dispatcher
        val tanks = service.loadAll(baseUrl, 1L)

        val fallbacks = service.probeCoverFallbacks(baseUrl, tanks)

        assertEquals(mapOf("TANK_0000000106" to arcId(1)), fallbacks.mapValues { it.value.arcid })
        assertEquals(listOf("/api/tankoubons/TANK_0000000106/thumbnail?no_fallback=true"), dispatcher.probePaths)
    }

    @Test
    fun probe200_noFallback_andAConfirmedCoverIsNeverProbedAgain() = runBlocking {
        val dispatcher = ProbeDispatcher(pageJson(1, tankJson("TANK_0000000107", "Alpha", 1)), 200)
        server.dispatcher = dispatcher
        val tanks = service.loadAll(baseUrl, 1L)

        assertTrue(service.probeCoverFallbacks(baseUrl, tanks).isEmpty())
        // A reopened list (new service) on the same server.
        assertTrue(TankListService(background).probeCoverFallbacks(baseUrl, tanks).isEmpty())

        assertEquals(1, dispatcher.probePaths.size)
    }

    @Test
    fun emptyTank_isNotProbed() = runBlocking {
        val dispatcher = ProbeDispatcher(pageJson(1, tankJson("TANK_0000000108", "Empty", 0)), 202)
        server.dispatcher = dispatcher
        val tanks = service.loadAll(baseUrl, 1L)

        assertTrue(service.probeCoverFallbacks(baseUrl, tanks).isEmpty())
        assertTrue("empty tank must not be probed", dispatcher.probePaths.isEmpty())
    }

    // ── members / auto-sort / restore / create ─────────────────────

    @Test
    fun members_keepsTankOrder() = runBlocking {
        server.enqueue(MockResponse().setBody(fullJson("TANK_0000000109", arcId(2) to "B", arcId(1) to "A")))

        val m = service.members(baseUrl, 3L, "TANK_0000000109")

        assertEquals(listOf(arcId(2), arcId(1)), m.members.map { it.arcid })
        assertEquals(listOf(arcId(2), arcId(1)), m.memberIdsInOrder)
        assertTrue(m.members.all { it.serverProfileId == 3L })
    }

    @Test
    fun autoSort_putsEpisodeOrder_andReturnsThePreviousOrder() = runBlocking {
        val id = "TANK_0000000110"
        server.enqueue(MockResponse().setBody(fullJson(id, arcId(1) to "第2话", arcId(2) to "番外", arcId(3) to "第1话")))
        server.enqueue(MockResponse().setBody("""{"success":1}"""))

        val previous = service.autoSort(baseUrl, id)

        assertEquals(listOf(arcId(1), arcId(2), arcId(3)), previous)
        assertTrue(server.awaitRequest().path!!.startsWith("/api/tankoubons/$id/full"))
        val put = server.awaitRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/tankoubons/$id", put.path)
        assertTrue(put.body.readUtf8().contains(""""archives":["${arcId(3)}","${arcId(1)}","${arcId(2)}"]"""))
    }

    @Test
    fun autoSort_alreadySorted_returnsNullWithoutPut() = runBlocking {
        server.enqueue(MockResponse().setBody(fullJson("TANK_0000000111", arcId(1) to "第1话", arcId(2) to "第2话")))

        assertNull(service.autoSort(baseUrl, "TANK_0000000111"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun restoreOrder_putsThePreviousOrderBack() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":1}"""))

        service.restoreOrder(baseUrl, "TANK_0000000112", listOf(arcId(2), arcId(1)))

        val put = server.awaitRequest()
        assertEquals("PUT", put.method)
        assertTrue(put.body.readUtf8().contains(""""archives":["${arcId(2)}","${arcId(1)}"]"""))
    }

    @Test
    fun create_returnsTheNewId_andLockedSurfacesAs423() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tankoubon_id":"TANK_0000000113","success":1}"""))
        assertEquals("TANK_0000000113", service.create(baseUrl, "NewTank"))

        server.enqueue(MockResponse().setResponseCode(423).setBody("""{"success":0,"error":"Locker is busy"}"""))
        try {
            service.create(baseUrl, "NewTank")
            fail("423 must propagate")
        } catch (e: LRRHttpException) {
            assertEquals(423, e.code)
        }
    }
}
