package com.hippo.ehviewer.gallery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class NextArchiveResolverTankTest {

    private val a1 = "a".repeat(40)
    private val a2 = "b".repeat(40)
    private val a3 = "c".repeat(40)
    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var resolver: NextArchiveResolver

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        resolver = NextArchiveResolver(
            OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        )
        ReadingContextStore.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ReadingContextStore.clear()
    }

    private fun tankCtx(anchor: String) = ReadingContext.Tankoubon(
        sourceProfileId = 7L,
        sourceBaseUrl = baseUrl,
        tankId = "TANK_1688616437",
        orderedMemberIds = listOf(a1, a2),
        pageOffsets = listOf(0, 20, 45),
        anchorArcid = anchor,
    )

    /** /full response with the given (arcid to pagecount) members in order. */
    private fun fullJson(vararg members: Pair<String, Int>): String {
        val ids = members.joinToString(",") { "\"${it.first}\"" }
        val data = members.joinToString(",") {
            """{"arcid":"${it.first}","title":"t","tags":"","isnew":"false",
               "extension":"zip","filename":"f.zip","pagecount":${it.second},"progress":0,"lastreadtime":0}"""
        }
        return """{"result":{"id":"TANK_1688616437","name":"T","summary":"","tags":"",
                  "progress":0,"archives":[$ids],"full_data":[$data]},"total":${members.size},"filtered":${members.size}}"""
    }

    @Test
    fun middleMember_resolvesNext_withSourceContext_andFreshOffsets() = runTest {
        ReadingContextStore.publish(tankCtx(a1))
        // Server-first membership refresh rides ahead of the metadata fetch.
        server.enqueue(MockResponse().setBody(fullJson(a1 to 20, a2 to 30)))
        server.enqueue(
            MockResponse().setBody(
                """{"arcid":"$a2","title":"Vol 2","tags":"","isnew":"false",
                   "extension":"zip","filename":"v2.zip","pagecount":30,"progress":0,"lastreadtime":0}"""
            )
        )

        val r = resolver.resolve(a1)

        val next = r as NextArchiveResolver.NextResult.Next
        assertEquals(a2, next.archive.arcid)
        assertEquals(7L, next.archive.serverProfileId)          // explicit source, not active
        val adv = next.advanced as ReadingContext.Tankoubon
        assertEquals(a2, adv.anchorArcid)
        // advanced context carries the SERVER's offsets, not the snapshot's
        assertEquals(listOf(0, 20, 50), adv.pageOffsets)
        assertEquals("/api/tankoubons/TANK_1688616437/full?page=-1", server.takeRequest().path)
        assertEquals("/api/archives/$a2/metadata", server.takeRequest().path)
    }

    @Test
    fun lastMember_isEndOfList() = runTest {
        ReadingContextStore.publish(tankCtx(a2))
        server.enqueue(MockResponse().setBody(fullJson(a1 to 20, a2 to 25)))
        assertEquals(NextArchiveResolver.NextResult.EndOfList, resolver.resolve(a2))
    }

    @Test
    fun anchorNotInTank_isNoContext() = runTest {
        // a stale context CAN hold an anchor whose id later left the tank:
        val stale = tankCtx(a1).copy(orderedMemberIds = listOf(a2))
        ReadingContextStore.publish(stale)
        server.enqueue(MockResponse().setBody(fullJson(a2 to 25)))
        assertEquals(NextArchiveResolver.NextResult.NoContext, resolver.resolve(a1))
    }

    @Test
    fun metadataFetchFailure_isError() = runTest {
        ReadingContextStore.publish(tankCtx(a1))
        server.enqueue(MockResponse().setBody(fullJson(a1 to 20, a2 to 25)))
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(resolver.resolve(a1) is NextArchiveResolver.NextResult.Error)
    }

    @Test
    fun staleSnapshot_memberRemovedOnServer_isEndOfList() = runTest {
        // 2026-07-05 on-device smoke: the published snapshot still held a
        // member that had been removed server-side (its post-removal reload
        // failed), and the end-of-book panel offered the REMOVED member as
        // "up next". Membership must be re-read from the server.
        val stale = tankCtx(a2).copy(orderedMemberIds = listOf(a1, a2, a3))
        ReadingContextStore.publish(stale)
        server.enqueue(MockResponse().setBody(fullJson(a1 to 20, a2 to 25)))

        assertEquals(NextArchiveResolver.NextResult.EndOfList, resolver.resolve(a2))
    }

    @Test
    fun membershipRefreshFails_fallsBackToSnapshot() = runTest {
        // Offline / flaky LAN: the snapshot is still better than nothing.
        ReadingContextStore.publish(tankCtx(a1))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(
            MockResponse().setBody(
                """{"arcid":"$a2","title":"Vol 2","tags":"","isnew":"false",
                   "extension":"zip","filename":"v2.zip","pagecount":25,"progress":0,"lastreadtime":0}"""
            )
        )

        val r = resolver.resolve(a1)

        assertEquals(a2, (r as NextArchiveResolver.NextResult.Next).archive.arcid)
    }

    // ----- Online search with folded tanks (groupby_tanks=true) -----
    // Windows are RAW (tanks stay in) so anchorIndex arithmetic matches what
    // the browse list showed; tanks are only skipped when picking "next".

    private fun onlineCtx(anchor: String, index: Int) = ReadingContext.OnlineSearch(
        sourceProfileId = 7L, sourceBaseUrl = baseUrl,
        filter = null, category = null, sortby = null, order = null,
        newonly = false, untaggedonly = false,
        anchorArcid = anchor, anchorIndex = index,
        groupbyTanks = true,
    )

    @Test
    fun onlineSearch_skipsTankEntries_keepsRawIndexing() = runTest {
        val a3 = "d".repeat(40)
        // Browse list (groupbyTanks=true) raw order: [a1, TANK_x, a3]; anchor a1 at raw index 0.
        ReadingContextStore.publish(onlineCtx(a1, 0))
        server.enqueue(
            MockResponse().setBody(
                """{"data":[
                   {"arcid":"$a1","title":"A1","tags":"","isnew":"false","extension":"zip","filename":"1.zip","pagecount":9,"progress":0,"lastreadtime":0},
                   {"arcid":"TANK_1688616437","title":"T","tags":"","isnew":"false","extension":"","filename":"","pagecount":0,"progress":0,"lastreadtime":0},
                   {"arcid":"$a3","title":"A3","tags":"","isnew":"false","extension":"zip","filename":"3.zip","pagecount":5,"progress":0,"lastreadtime":0}
                   ],"draw":1,"recordsFiltered":3,"recordsTotal":3}"""
            )
        )

        val r = resolver.resolve(a1)

        val next = r as NextArchiveResolver.NextResult.Next
        assertEquals(a3, next.archive.arcid)          // tank skipped
        val adv = next.advanced as ReadingContext.OnlineSearch
        assertEquals(2, adv.anchorIndex)              // RAW index of a3, not filtered index
        assertTrue(server.takeRequest().path!!.contains("groupby_tanks=true"))
    }

    @Test
    fun onlineSearch_scansForwardAcrossWindows_whenTailIsAllTanks() = runTest {
        val a4 = "e".repeat(40)
        ReadingContextStore.publish(onlineCtx(a1, 0))
        // Window 1 (raw start 0): [a1, TANK_x] with 4 records total — the tail
        // after the anchor is all tanks, forcing a follow-up window fetch.
        server.enqueue(
            MockResponse().setBody(
                """{"data":[
                   {"arcid":"$a1","title":"A1","tags":"","isnew":"false","extension":"zip","filename":"1.zip","pagecount":9,"progress":0,"lastreadtime":0},
                   {"arcid":"TANK_1688616437","title":"T1","tags":"","isnew":"false","extension":"","filename":"","pagecount":0,"progress":0,"lastreadtime":0}
                   ],"draw":1,"recordsFiltered":4,"recordsTotal":4}"""
            )
        )
        // Window 2 (raw start 2): [TANK_y, a4] → first non-tank is a4 at raw index 3.
        server.enqueue(
            MockResponse().setBody(
                """{"data":[
                   {"arcid":"TANK_9999999999","title":"T2","tags":"","isnew":"false","extension":"","filename":"","pagecount":0,"progress":0,"lastreadtime":0},
                   {"arcid":"$a4","title":"A4","tags":"","isnew":"false","extension":"zip","filename":"4.zip","pagecount":7,"progress":0,"lastreadtime":0}
                   ],"draw":2,"recordsFiltered":4,"recordsTotal":4}"""
            )
        )

        val r = resolver.resolve(a1)

        val next = r as NextArchiveResolver.NextResult.Next
        assertEquals(a4, next.archive.arcid)
        assertEquals(3, (next.advanced as ReadingContext.OnlineSearch).anchorIndex)  // RAW index
        // start=0 omits the query param entirely (LRRSearchApi only sends start > 0).
        assertFalse(server.takeRequest().path!!.contains("start="))
        assertTrue(server.takeRequest().path!!.contains("start=2"))
    }
}
