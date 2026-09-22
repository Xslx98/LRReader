package com.lanraragi.reader.tankoubon

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The detail page's tank names come from a short-lived list copy (audit 2026-09-22 A60). */
class TankListCacheTest {

    private val server = MockWebServer()
    private val listCalls = AtomicInteger()
    private val client = OkHttpClient()
    private lateinit var base: String

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                listCalls.incrementAndGet()
                return MockResponse().setBody(
                    """{"result":[{"id":"TANK_1","name":"One","archives":["a"]}],"total":1,"filtered":1}"""
                )
            }
        }
        server.start()
        base = server.url("").toString().removeSuffix("/")
        TankListCache.invalidate(base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun aFreshCopyIsReusedWithinTheTtl() = runTest {
        TankListCache.get(client, base, now = 1_000L)
        val second = TankListCache.get(client, base, now = 1_000L + TankListCache.TTL_MS - 1)
        assertEquals(1, listCalls.get())
        assertEquals("One", second.single().name)
    }

    @Test
    fun anExpiredOrInvalidatedCopyIsRefetched() = runTest {
        TankListCache.get(client, base, now = 1_000L)
        TankListCache.get(client, base, now = 1_000L + TankListCache.TTL_MS + 1)
        assertEquals(2, listCalls.get())
        TankListCache.invalidate(base)
        TankListCache.get(client, base, now = 1_000L + TankListCache.TTL_MS + 2)
        assertEquals(3, listCalls.get())
    }
}
