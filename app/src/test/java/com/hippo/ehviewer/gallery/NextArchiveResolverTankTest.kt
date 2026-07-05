package com.hippo.ehviewer.gallery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class NextArchiveResolverTankTest {

    private val a1 = "a".repeat(40)
    private val a2 = "b".repeat(40)
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

    @Test
    fun middleMember_resolvesNext_withSourceContext() = runTest {
        ReadingContextStore.publish(tankCtx(a1))
        server.enqueue(
            MockResponse().setBody(
                """{"arcid":"$a2","title":"Vol 2","tags":"","isnew":"false",
                   "extension":"zip","filename":"v2.zip","pagecount":25,"progress":0,"lastreadtime":0}"""
            )
        )

        val r = resolver.resolve(a1)

        val next = r as NextArchiveResolver.NextResult.Next
        assertEquals(a2, next.archive.arcid)
        assertEquals(7L, next.archive.serverProfileId)          // explicit source, not active
        assertEquals(a2, (next.advanced as ReadingContext.Tankoubon).anchorArcid)
        assertEquals("/api/archives/$a2/metadata", server.takeRequest().path)
    }

    @Test
    fun lastMember_isEndOfList() = runTest {
        ReadingContextStore.publish(tankCtx(a2))
        assertEquals(NextArchiveResolver.NextResult.EndOfList, resolver.resolve(a2))
    }

    @Test
    fun anchorNotInTank_isNoContext() = runTest {
        // a stale context CAN hold an anchor whose id later left the tank:
        val stale = tankCtx(a1).copy(orderedMemberIds = listOf(a2))
        ReadingContextStore.publish(stale)
        assertEquals(NextArchiveResolver.NextResult.NoContext, resolver.resolve(a1))
    }

    @Test
    fun metadataFetchFailure_isError() = runTest {
        ReadingContextStore.publish(tankCtx(a1))
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(resolver.resolve(a1) is NextArchiveResolver.NextResult.Error)
    }
}
