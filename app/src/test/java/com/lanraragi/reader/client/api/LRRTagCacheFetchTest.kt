package com.lanraragi.reader.client.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.data.LRRTagStat
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.server.ServerSession
import com.lanraragi.reader.stubNetworkModule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [LRRTagCache.getTags] fetches from the server that is active when the fetch
 * starts, over the client the caller passes (not the shared one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LRRTagCacheFetchTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        SecureCredentialStore.initialize(ctx)
        SecureCredentialStore.initializeForTesting(
            ctx.getSharedPreferences("tag_cache_fetch_test", Context.MODE_PRIVATE)
        )
        // ServerConnections.active() reads the shared client; a call over it fails,
        // so only a fetch over the caller's [client] can succeed.
        val shared = OkHttpClient.Builder()
            .addInterceptor { throw IOException("shared client used") }
            .build()
        ServiceRegistry.initializeForTest(network = stubNetworkModule(shared))
        LRRTagCache.clear()
    }

    @After
    fun tearDown() {
        LRRTagCache.clear()
        SecureCredentialStore.clear()
        server.shutdown()
    }

    @Test
    fun fetchesStatsFromTheActiveServer() = runBlocking {
        ServerSession.setServerUrl(server.url("/").toString())
        server.enqueue(MockResponse().setBody("""[{"namespace":"artist","text":"john","weight":3}]"""))

        val tags = LRRTagCache.getTags(client)

        assertEquals(listOf(LRRTagStat("artist", "john", 3)), tags)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/database/stats", request.requestUrl!!.encodedPath)
    }

    @Test
    fun noActiveServerYieldsEmptyAndBacksOff() = runBlocking {
        val tags = LRRTagCache.getTags(client)

        assertTrue(tags.isEmpty())
        assertEquals(0, server.requestCount)
        // The failed attempt counts: no refetch on every search-bar open.
        assertEquals(false, LRRTagCache.needsRefresh())
    }
}
