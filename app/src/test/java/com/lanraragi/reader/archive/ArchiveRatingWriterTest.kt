package com.lanraragi.reader.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.server.ServerConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.lanraragi.reader.stubNetworkModule
import com.lanraragi.reader.ServiceRegistry

/**
 * [ArchiveRatingWriter] against a MockWebServer LANraragi: the rating is
 * merged into the server's CURRENT tags, a saved rating reaches the download
 * row, a failure reaches the caller instead, and only a newer rating of the
 * same archive supersedes a pending write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ArchiveRatingWriterTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var conn: ServerConnection
    private val rows = CopyOnWriteArrayList<Pair<String, Float>>()
    private val puts = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Context>()
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        conn = ServerConnection(1L, server.url("").toString().removeSuffix("/"), client)
        // Suite-order robustness: an earlier class may leave ServiceRegistry with a
        // real NetworkMonitor that reports offline, and the API then refuses requests.
        ServiceRegistry.initializeForTest(network = stubNetworkModule(client))
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun writer(connections: suspend (Long) -> ServerConnection = { conn }) =
        ArchiveRatingWriter(scope, connections) { arcid, rating -> rows += arcid to rating }

    private fun serve(getCode: Int = 200) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "PUT") {
                    puts += request
                    return MockResponse().setBody("""{"success":1}""")
                }
                if (getCode != 200) return MockResponse().setResponseCode(getCode).setBody("{}")
                val arcid = request.path.orEmpty().split('/')[3]
                return MockResponse().setBody(
                    """{"arcid":"$arcid","title":"t","tags":"artist:a, rating:⭐","isnew":"false",""" +
                        """"extension":"zip","pagecount":1,"progress":0,"lastreadtime":0}"""
                )
            }
        }
    }

    private fun putTags(req: RecordedRequest): String = req.requestUrl!!.queryParameter("tags").orEmpty()

    @Test
    fun submit_mergesIntoTheServerTags_thenUpdatesTheDownloadRow() = runBlocking {
        serve()
        val failures = CopyOnWriteArrayList<Exception>()

        writer().submit(1L, ARC_A, 4f) { failures += it }.join()

        assertEquals("artist:a, rating:⭐⭐⭐⭐", putTags(puts.single()))
        assertEquals(listOf(ARC_A to 4f), rows.toList())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun submit_failure_reachesTheCaller_andLeavesTheRowAlone() = runBlocking {
        serve(getCode = 404)
        val failures = CopyOnWriteArrayList<Exception>()

        writer().submit(1L, ARC_A, 4f) { failures += it }.join()

        assertEquals(1, failures.size)
        assertTrue(puts.isEmpty())
        assertTrue(rows.isEmpty())
    }

    @Test
    fun aNewerRatingOfTheSameArchive_supersedesThePendingOne_only() = runBlocking {
        serve()
        val gate = CompletableDeferred<Unit>()
        val w = writer { gate.await(); conn }

        val first = w.submit(1L, ARC_A, 3f) { }
        val second = w.submit(1L, ARC_A, 4f) { }
        val other = w.submit(1L, ARC_B, 5f) { }
        gate.complete(Unit)
        withTimeout(5_000) { listOf(first, second, other).forEach { it.join() } }

        assertTrue(first.isCancelled)
        val byArchive = puts.associate { it.requestUrl!!.pathSegments[2] to putTags(it) }
        assertEquals(
            mapOf(ARC_A to "artist:a, rating:⭐⭐⭐⭐", ARC_B to "artist:a, rating:⭐⭐⭐⭐⭐"),
            byArchive,
        )
    }

    private companion object {
        val ARC_A = "a".repeat(40)
        val ARC_B = "b".repeat(40)
    }
}
