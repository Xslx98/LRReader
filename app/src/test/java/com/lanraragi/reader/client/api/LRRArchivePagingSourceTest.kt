package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class LRRArchivePagingSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun createPagingSource(
        filter: String? = null,
        category: String? = null,
        sortby: String? = "date_added",
        order: String? = "desc",
        newonly: Boolean = false,
        untaggedonly: Boolean = false,
        // Tests MUST inject the fold decision: the production default reads
        // SharedPreferences-backed settings, unavailable in plain JVM tests.
        includeTanks: () -> Boolean = { false },
    ) = LRRArchivePagingSource(
        client = client,
        baseUrl = baseUrl,
        filter = filter,
        category = category,
        sortby = sortby,
        order = order,
        newonly = newonly,
        untaggedonly = untaggedonly,
        includeTanksProvider = includeTanks
    )

    // ---- JSON fixtures ----

    private fun archiveJson(id: String, title: String): String {
        // OpenAPI v0.9.6 requires arcid to be a 40-char SHA-1 hex; pad short
        // fixture ids with '0' so requireValidArcid (used by LRRArchive helpers
        // like getThumbnailUrl) doesn't reject them mid-test.
        val arcid = id.padEnd(40, '0').take(40)
        return """{"arcid":"$arcid","title":"$title","tags":"","isnew":"false","extension":"zip","filename":"$arcid.zip","pagecount":10,"progress":0,"lastreadtime":0}"""
    }

    private fun searchResultJson(archives: List<Pair<String, String>>, total: Int): String {
        val dataJson = archives.joinToString(",") { (id, title) -> archiveJson(id, title) }
        return """{"data":[$dataJson],"draw":1,"recordsFiltered":$total,"recordsTotal":$total}"""
    }

    // ---- Tests ----

    @Test
    fun load_firstPage_returnsPageWithNullPrevKey() = runTest {
        val archives = (1..10).map { "a$it" to "Archive $it" }
        server.enqueue(MockResponse().setBody(searchResultJson(archives, 50)))

        val source = createPagingSource()
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false)
        )

        assertTrue("Expected LoadResult.Page", result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(10, page.data.size)
        assertEquals("Archive 1", page.data[0].title)
        assertNull(page.prevKey)
        assertEquals(1, page.nextKey)
    }

    @Test
    fun load_firstPage_sendsCorrectStartOffset() = runTest {
        server.enqueue(
            MockResponse().setBody(searchResultJson(listOf("a1" to "A"), 1))
        )
        val source = createPagingSource()
        source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        val request = server.takeRequest()
        // Page 0 with loadSize 100 → start=0 (omitted from URL since start <= 0).
        // groupby_tanks is always sent (false here: the test helper pins folding off).
        assertEquals("/api/search?sortby=date_added&order=desc&groupby_tanks=false", request.path)
    }

    @Test
    fun load_secondPage_sendsCorrectStartOffset() = runTest {
        val archives = (1..100).map { "a$it" to "Archive $it" }
        server.enqueue(MockResponse().setBody(searchResultJson(archives, 300)))

        val source = createPagingSource()
        val result = source.load(
            PagingSource.LoadParams.Append(key = 1, loadSize = 100, placeholdersEnabled = false)
        )

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(0, page.prevKey)
        assertEquals(2, page.nextKey)

        val request = server.takeRequest()
        assertTrue("Path should contain start=100", request.path!!.contains("start=100"))
    }

    @Test
    fun load_lastPage_returnsNullNextKey() = runTest {
        // Return fewer items than loadSize to indicate last page
        val archives = listOf("a1" to "Last Archive")
        server.enqueue(MockResponse().setBody(searchResultJson(archives, 1)))

        val source = createPagingSource()
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(1, page.data.size)
        assertNull(page.nextKey)
    }

    @Test
    fun load_dropsTankoubonEntries() = runTest {
        // Folding OFF: the server may still return 15-char TANK_ ids mixed in.
        // toArchive()/getThumbnailUrl()/requireValidArcid() can't render a TANK_ id,
        // so the paging source must drop them instead of failing the whole page.
        val tank = """{"arcid":"TANK_1688616437","title":"A tank","tags":"","isnew":"false","extension":"zip","filename":"t.zip","pagecount":1,"progress":0,"lastreadtime":0}"""
        val real = archiveJson("a1", "Real")
        server.enqueue(
            MockResponse().setBody("""{"data":[$tank,$real],"draw":1,"recordsFiltered":2,"recordsTotal":2}""")
        )

        val source = createPagingSource()
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(1, page.data.size)
        assertFalse(page.data.any { it.arcid.startsWith("TANK_") })
    }

    @Test
    fun load_includeTanks_mapsTankPseudoEntries_andSendsGroupbyTanksTrue() = runTest {
        // Folding ON: TANK_ rows stay in the page as display-only pseudo-Archives
        // carrying the tank thumbnail route, and the request asks the server to fold.
        val tank = """{"arcid":"TANK_1688616437","title":"A tank","tags":"","isnew":"false","extension":"","filename":"","pagecount":3,"progress":0,"lastreadtime":0}"""
        val real = archiveJson("a1", "Real")
        server.enqueue(
            MockResponse().setBody("""{"data":[$tank,$real],"draw":1,"recordsFiltered":2,"recordsTotal":2}""")
        )

        val source = createPagingSource(includeTanks = { true })
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(2, page.data.size)
        assertEquals("TANK_1688616437", page.data[0].arcid)
        assertTrue(
            "tank pseudo-entry must use the tank thumbnail route, was: ${page.data[0].thumbnailUrl}",
            page.data[0].thumbnailUrl.contains("/api/tankoubons/TANK_1688616437/thumbnail"),
        )
        assertTrue(server.takeRequest().path!!.contains("groupby_tanks=true"))
    }

    @Test
    fun load_emptyResult_returnsEmptyPageWithNullKeys() = runTest {
        server.enqueue(MockResponse().setBody(searchResultJson(emptyList(), 0)))

        val source = createPagingSource()
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertTrue(page.data.isEmpty())
        assertNull(page.prevKey)
        assertNull(page.nextKey)
    }

    @Test
    fun load_networkError_returnsLoadResultError() = runTest {
        // Shut down the server to cause a connection error
        server.shutdown()

        val source = createPagingSource()
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        assertTrue("Expected LoadResult.Error", result is PagingSource.LoadResult.Error)
    }

    @Test
    fun load_httpError_returnsLoadResultError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val source = createPagingSource()
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        assertTrue("Expected LoadResult.Error", result is PagingSource.LoadResult.Error)
    }

    @Test
    fun load_passesFilterAndCategoryParams() = runTest {
        server.enqueue(
            MockResponse().setBody(searchResultJson(listOf("a1" to "A"), 1))
        )

        val source = createPagingSource(filter = "my search", category = "SET_aaaaaaaaaa")
        source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        val request = server.takeRequest()
        val path = request.path!!
        assertTrue("Path should contain filter param", path.contains("filter="))
        assertTrue("Path should contain category=SET_aaaaaaaaaa", path.contains("category=SET_aaaaaaaaaa"))
    }

    @Test
    fun load_passesSortParams() = runTest {
        server.enqueue(
            MockResponse().setBody(searchResultJson(listOf("a1" to "A"), 1))
        )

        val source = createPagingSource(sortby = "title", order = "asc")
        source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        val request = server.takeRequest()
        val path = request.path!!
        assertTrue("Path should contain sortby=title", path.contains("sortby=title"))
        assertTrue("Path should contain order=asc", path.contains("order=asc"))
    }

    @Test
    fun load_middlePage_hasBothPrevAndNextKeys() = runTest {
        val archives = (1..100).map { "a$it" to "Archive $it" }
        server.enqueue(MockResponse().setBody(searchResultJson(archives, 500)))

        val source = createPagingSource()
        val result = source.load(
            PagingSource.LoadParams.Append(key = 2, loadSize = 100, placeholdersEnabled = false)
        )

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(1, page.prevKey)
        assertEquals(3, page.nextKey)
    }

    @Test
    fun getRefreshKey_returnsNullWhenNoAnchor() {
        val source = createPagingSource()
        val state = PagingState<Int, Archive>(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig(pageSize = 50),
            leadingPlaceholderCount = 0
        )
        assertNull(source.getRefreshKey(state))
    }

    @Test
    fun getRefreshKey_returnsKeyFromClosestPage() {
        val source = createPagingSource()
        val archive = Archive(
            arcid = "test", title = "Test", tags = emptyMap(),
            pagecount = 0, progress = 0, extension = "", filename = "",
            thumbnailUrl = "", rating = 0f, isnew = false, lastreadtime = 0,
            summary = null, serverProfileId = 0
        )
        val page = PagingSource.LoadResult.Page(
            data = listOf(archive),
            prevKey = 1,
            nextKey = 3
        )
        val state = PagingState(
            pages = listOf(page),
            anchorPosition = 0,
            config = PagingConfig(pageSize = 50),
            leadingPlaceholderCount = 0
        )
        // closestPageToPosition(0) -> page with prevKey=1 -> 1+1 = 2
        assertEquals(2, source.getRefreshKey(state))
    }

    // ---- newonly / untaggedonly filter edge cases ----

    @Test
    fun load_withNewOnlyFilter_passesParameter() = runTest {
        server.enqueue(
            MockResponse().setBody(searchResultJson(listOf("a1" to "New Archive"), 1))
        )

        val source = createPagingSource(newonly = true)
        source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        val request = server.takeRequest()
        val path = request.path!!
        assertTrue("Path should contain newonly=true", path.contains("newonly=true"))
    }

    @Test
    fun load_withUntaggedOnlyFilter_passesParameter() = runTest {
        server.enqueue(
            MockResponse().setBody(searchResultJson(listOf("a1" to "Untagged Archive"), 1))
        )

        val source = createPagingSource(untaggedonly = true)
        source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        val request = server.takeRequest()
        val path = request.path!!
        assertTrue("Path should contain untaggedonly=true", path.contains("untaggedonly=true"))
    }

    @Test
    fun load_withBothNewAndUntaggedFilters_passesBothParameters() = runTest {
        server.enqueue(
            MockResponse().setBody(searchResultJson(listOf("a1" to "Archive"), 1))
        )

        val source = createPagingSource(newonly = true, untaggedonly = true)
        source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        val request = server.takeRequest()
        val path = request.path!!
        assertTrue("Path should contain newonly=true", path.contains("newonly=true"))
        assertTrue("Path should contain untaggedonly=true", path.contains("untaggedonly=true"))
    }

    @Test
    fun load_withoutNewOnlyFilter_omitsParameter() = runTest {
        server.enqueue(
            MockResponse().setBody(searchResultJson(listOf("a1" to "Archive"), 1))
        )

        val source = createPagingSource(newonly = false, untaggedonly = false)
        source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        )

        val request = server.takeRequest()
        val path = request.path!!
        assertFalse("Path should NOT contain newonly", path.contains("newonly"))
        assertFalse("Path should NOT contain untaggedonly", path.contains("untaggedonly"))
    }
}
