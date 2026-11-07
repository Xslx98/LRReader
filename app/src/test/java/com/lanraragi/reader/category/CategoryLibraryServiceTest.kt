package com.lanraragi.reader.category

import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.server.ServerConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [CategoryLibraryService]: the categories screen's list comes back named and
 * pinned first, a write the user confirmed finishes even when the screen that
 * awaited it goes away, and nothing is sent without a configured server.
 */
class CategoryLibraryServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var conn: ServerConnection
    private lateinit var writeScope: CoroutineScope
    private val writes = CopyOnWriteArrayList<String>()
    private val writeBodies = CopyOnWriteArrayList<String>()

    /** When set, write responses wait for it (lets a test cancel the caller mid-write). */
    @Volatile
    private var holdWrites: CountDownLatch? = null

    @Volatile
    private var failWrites = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") {
                    return MockResponse().setBody(
                        """[{"id":"SET_1000000001","name":"Plain","archives":["a1","a2"],"pinned":"0","search":""},""" +
                            """{"id":"SET_1000000002","name":"","archives":[],"pinned":"1","search":""},""" +
                            """{"id":"SET_1000000003","name":"Top","archives":[],"pinned":1,"search":""},""" +
                            """{"id":"SET_1000000004","name":"Dyn","archives":[],"pinned":"0","search":"artist:x"}]"""
                    )
                }
                holdWrites?.await(5, TimeUnit.SECONDS)
                writes.add("${request.method} ${request.path}")
                writeBodies.add(request.body.readUtf8())
                if (failWrites) return MockResponse().setResponseCode(423)
                return MockResponse().setBody("""{"success":1,"category_id":"SET_1000000009"}""")
            }
        }
        server.start()
        val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        conn = ServerConnection(1L, server.url("").toString().removeSuffix("/"), client)
        writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        holdWrites?.countDown()
        writeScope.cancel()
        server.shutdown()
    }

    @Test
    fun load_dropsNamelessEntries_andPutsPinnedFirst() = runBlocking {
        val loaded = CategoryLibraryService({ conn }, writeScope).load()!!

        assertEquals(listOf("Top", "Plain", "Dyn"), loaded.map { it.name })
    }

    @Test
    fun load_keepsEveryFieldTheScreenReads() = runBlocking {
        val loaded = CategoryLibraryService({ conn }, writeScope).load()!!.associateBy { it.name }

        val top = loaded.getValue("Top")
        assertEquals("SET_1000000003", top.id)
        assertTrue("numeric pinned from the server", top.isPinned())
        assertFalse(top.isDynamic())

        val plain = loaded.getValue("Plain")
        assertEquals(listOf("a1", "a2"), plain.archives)
        assertFalse(plain.isPinned())

        val dyn = loaded.getValue("Dyn")
        assertEquals("artist:x", dyn.search)
        assertTrue(dyn.isDynamic())
    }

    /**
     * The server applies `search || old` and treats an omitted `pinned` as
     * unpinned, so what an edit builds from a loaded category has to survive to
     * the wire. Argument choice mirrors the Scene's pin toggle and rename, which
     * cannot run on the JVM; this owns the mapper plus the form encoding.
     */
    @Test
    fun editBuiltFromALoadedCategory_carriesItsLoadedValuesToTheWire() = runBlocking {
        val service = CategoryLibraryService({ conn }, writeScope)
        val loaded = service.load()!!.associateBy { it.name }
        val dyn = loaded.getValue("Dyn")
        val top = loaded.getValue("Top")

        service.update(dyn.id!!, dyn.name!!, dyn.search, !dyn.isPinned())
        service.update(top.id!!, "Renamed", top.search, top.isPinned())
        service.update(top.id!!, top.name!!, top.search, !top.isPinned())

        assertEquals(
            listOf("name=Dyn&search=artist%3Ax&pinned=true", "name=Renamed&pinned=true", "name=Top"),
            writeBodies,
        )
    }

    @Test
    fun noServer_loadsNothing_andSendsNoWrite() = runBlocking {
        val service = CategoryLibraryService({ null }, writeScope)

        assertNull(service.load())
        assertFalse(service.create("New", null, false))
        assertFalse(service.delete("SET_1000000001"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun writes_reachTheServer() = runBlocking {
        val service = CategoryLibraryService({ conn }, writeScope)

        assertTrue(service.create("New", null, true))
        assertTrue(service.update("SET_1000000001", "Renamed", "artist:y", false))
        assertTrue(service.delete("SET_1000000001"))

        assertEquals(
            listOf("PUT /api/categories", "PUT /api/categories/SET_1000000001", "DELETE /api/categories/SET_1000000001"),
            writes.map { it.substringBefore('?') },
        )
    }

    @Test
    fun write_failure_reachesTheCaller() = runBlocking {
        failWrites = true
        try {
            CategoryLibraryService({ conn }, writeScope).delete("SET_1000000001")
            fail("expected the 423 to propagate")
        } catch (e: LRRHttpException) {
            assertEquals(423, e.code)
        }
    }

    @Test
    fun write_finishes_whenTheCallerIsCancelled() = runBlocking {
        val hold = CountDownLatch(1)
        holdWrites = hold
        val service = CategoryLibraryService({ conn }, writeScope)
        val caller = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            service.delete("SET_1000000001")
        }
        // The write is on the server's side, waiting: the screen goes away now.
        withTimeout(5_000) { while (server.requestCount == 0) yield() }
        val write = writeScope.coroutineContext.job.children.single() as Deferred<*>
        caller.cancelAndJoin()
        hold.countDown()

        withTimeout(5_000) { write.join() }
        assertFalse(write.isCancelled)
        assertNull(write.getCompletionExceptionOrNull())
        assertEquals(listOf("DELETE /api/categories/SET_1000000001"), writes)
    }
}
