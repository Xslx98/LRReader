package com.lanraragi.reader.archive

import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.server.ServerConnection
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [ArchiveSearchSource] against a MockWebServer `/api/search`. Offsets are raw
 * item offsets, not dense page indexes: the server returns archives_per_page
 * rows per request, so the only safe advance is by the count actually
 * returned and the only safe termination signal is recordsFiltered (NET-2).
 */
class ArchiveSearchSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var conn: ServerConnection

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        conn = ServerConnection(profileId = 4L, baseUrl = server.url("").toString().removeSuffix("/"), client = client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // Tests MUST inject the fold and hide-completed decisions: the production
    // defaults read SharedPreferences-backed settings, unavailable in plain
    // JVM tests.
    private fun source(includeTanks: Boolean = false, hideCompleted: Boolean = false) =
        ArchiveSearchSource(includeTanks = { includeTanks }, hideCompleted = { hideCompleted })

    private suspend fun load(
        query: ArchiveSearchSource.Query = ArchiveSearchSource.Query(sortby = "date_added", order = "desc"),
        offset: Int = 0,
        source: ArchiveSearchSource = source(),
    ) = source.load(conn, query, offset)

    // ---- JSON fixtures ----

    private fun archiveJson(id: String, title: String): String {
        // OpenAPI v0.9.6 requires arcid to be a 40-char SHA-1 hex; pad short
        // fixture ids with '0' so requireValidArcid doesn't reject them.
        val arcid = id.padEnd(40, '0').take(40)
        return """{"arcid":"$arcid","title":"$title","tags":"","isnew":"false","extension":"zip",""" +
            """"filename":"$arcid.zip","pagecount":10,"progress":0,"lastreadtime":0}"""
    }

    private fun searchResultJson(archives: List<Pair<String, String>>, total: Int): String {
        val dataJson = archives.joinToString(",") { (id, title) -> archiveJson(id, title) }
        return """{"data":[$dataJson],"draw":1,"recordsFiltered":$total,"recordsTotal":$total}"""
    }

    private fun enqueue(count: Int, total: Int) {
        server.enqueue(MockResponse().setBody(searchResultJson((1..count).map { "a$it" to "Archive $it" }, total)))
    }

    // ---- offsets ----

    @Test
    fun firstPage_returnsItsRowsAndTheNextOffset() = runTest {
        enqueue(count = 10, total = 50)

        val page = load()

        assertEquals(10, page.items.size)
        assertEquals("Archive 1", page.items[0].title)
        assertEquals(10, page.nextOffset)
    }

    @Test
    fun serverPageSmallerThanTheNominalSize_continuesFromTheRowsReturned() = runTest {
        // archives_per_page=20 on the server: 100 records exist, so the list
        // must continue from offset 20 — the pre-NET-2 heuristic
        // (data.size >= page size) silently ended the list here.
        enqueue(count = 20, total = 100)

        assertEquals(20, load().nextOffset)
    }

    @Test
    fun serverPageLargerThanTheNominalSize_advancesByTheActualCount() = runTest {
        // archives_per_page=100: the next offset must be 100, not 50 —
        // advancing by 50 re-requests rows 50-99 and shows duplicates.
        enqueue(count = 100, total = 300)

        assertEquals(100, load().nextOffset)
    }

    @Test
    fun laterPage_sendsItsOffsetAndChainsFromIt() = runTest {
        enqueue(count = 100, total = 300)

        val page = load(offset = 100)

        assertEquals(200, page.nextOffset)
        assertTrue(server.awaitRequest().path!!.contains("start=100"))
    }

    @Test
    fun emptyPageClaimingMoreRecords_endsTheList() = runTest {
        // Malformed/edge server response: zero rows but recordsFiltered > 0.
        // Advancing by zero would re-request the same offset forever.
        enqueue(count = 0, total = 100)

        val page = load()

        assertTrue(page.items.isEmpty())
        assertNull(page.nextOffset)
    }

    @Test
    fun lastPage_hasNoNextOffset() = runTest {
        enqueue(count = 1, total = 1)

        val page = load()

        assertEquals(1, page.items.size)
        assertNull(page.nextOffset)
    }

    @Test
    fun emptyResult_hasNoRowsAndNoNextOffset() = runTest {
        enqueue(count = 0, total = 0)

        val page = load()

        assertTrue(page.items.isEmpty())
        assertNull(page.nextOffset)
    }

    // ---- tank folding ----

    @Test
    fun droppedTank_stillAdvancesTheOffsetByTheRawCount() = runTest {
        // A dropped TANK_ row still occupies a server-side offset slot: the
        // next request must skip it, otherwise the row after the tank would
        // be fetched twice.
        val tank = """{"arcid":"TANK_1688616437","title":"A tank","tags":"","isnew":"false","extension":"zip",""" +
            """"filename":"t.zip","pagecount":1,"progress":0,"lastreadtime":0}"""
        server.enqueue(
            MockResponse().setBody(
                """{"data":[$tank,${archiveJson("a1", "Real")}],"draw":1,"recordsFiltered":4,"recordsTotal":4}"""
            )
        )

        val page = load()

        assertEquals(1, page.items.size)
        assertEquals(2, page.nextOffset)
    }

    @Test
    fun includeTanks_mapsTankPseudoEntries_andAsksTheServerToFold() = runTest {
        // Folding ON: TANK_ rows stay in the page as display-only pseudo-Archives
        // carrying the tank thumbnail route and the connection's profile.
        val tank = """{"arcid":"TANK_1688616437","title":"A tank","tags":"","isnew":"false","extension":"",""" +
            """"filename":"","pagecount":3,"progress":0,"lastreadtime":0}"""
        server.enqueue(
            MockResponse().setBody(
                """{"data":[$tank,${archiveJson("a1", "Real")}],"draw":1,"recordsFiltered":2,"recordsTotal":2}"""
            )
        )

        val page = load(source = source(includeTanks = true))

        assertEquals(2, page.items.size)
        assertEquals("TANK_1688616437", page.items[0].arcid)
        assertEquals(4L, page.items[0].serverProfileId)
        assertTrue(
            "tank pseudo-entry must use the tank thumbnail route, was: ${page.items[0].thumbnailUrl}",
            page.items[0].thumbnailUrl.contains("/api/tankoubons/TANK_1688616437/thumbnail"),
        )
        assertTrue(server.awaitRequest().path!!.contains("groupby_tanks=true"))
    }

    // ---- request parameters ----

    @Test
    fun firstPage_requestOmitsStart_andAlwaysSendsTheFoldDecision() = runTest {
        enqueue(count = 1, total = 1)

        load()

        // start <= 0 is omitted; groupby_tanks is always sent (false: pinned off).
        assertEquals("/api/search?sortby=date_added&order=desc&groupby_tanks=false", server.awaitRequest().path)
    }

    @Test
    fun passesFilterAndCategory() = runTest {
        enqueue(count = 1, total = 1)

        load(ArchiveSearchSource.Query(filter = "my search", category = "SET_aaaaaaaaaa"))

        val path = server.awaitRequest().path!!
        assertTrue("Path should contain filter param", path.contains("filter="))
        assertTrue("Path should contain category=SET_aaaaaaaaaa", path.contains("category=SET_aaaaaaaaaa"))
    }

    @Test
    fun passesSort() = runTest {
        enqueue(count = 1, total = 1)

        load(ArchiveSearchSource.Query(sortby = "title", order = "asc"))

        val path = server.awaitRequest().path!!
        assertTrue(path.contains("sortby=title"))
        assertTrue(path.contains("order=asc"))
    }

    @Test
    fun passesNewOnlyAndUntaggedOnly_onlyWhenSet() = runTest {
        enqueue(count = 1, total = 1)
        enqueue(count = 1, total = 1)

        load(ArchiveSearchSource.Query(newonly = true, untaggedonly = true))
        load(ArchiveSearchSource.Query())

        val withFlags = server.awaitRequest().path!!
        assertTrue(withFlags.contains("newonly=true"))
        assertTrue(withFlags.contains("untaggedonly=true"))
        val without = server.awaitRequest().path!!
        assertFalse(without.contains("newonly"))
        assertFalse(without.contains("untaggedonly"))
    }

    @Test
    fun hideCompleted_isDecidedPerRequest() = runTest {
        enqueue(count = 1, total = 1)
        enqueue(count = 1, total = 1)

        load(source = source(hideCompleted = true))
        load(source = source(hideCompleted = false))

        assertTrue(server.awaitRequest().path!!.contains("hidecompleted=true"))
        assertFalse(server.awaitRequest().path!!.contains("hidecompleted"))
    }

    // ---- failures ----

    @Test
    fun httpError_propagates() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        try {
            load()
            fail("a 500 must propagate")
        } catch (expected: Exception) {
            // ok
        }
    }

    @Test
    fun networkError_propagates() = runTest {
        server.shutdown()

        try {
            load()
            fail("a connection failure must propagate")
        } catch (expected: Exception) {
            // ok
        }
    }
}
