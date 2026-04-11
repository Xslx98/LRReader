package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import androidx.paging.PagingSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Integration tests for multi-page search through [LRRArchivePagingSource].
 * Focuses on scenarios not covered by [LRRArchivePagingSourceTest]:
 * - Multi-page sequential loading
 * - Invalidation on search parameter change
 * - Combined filter parameters
 * - Server returning fewer items than page size (last page detection)
 */
class LRRSearchPaginationTest {

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
    ) = LRRArchivePagingSource(
        client = client,
        baseUrl = baseUrl,
        filter = filter,
        category = category,
        sortby = sortby,
        order = order,
        newonly = false,
        untaggedonly = false
    )

    private fun archiveJson(id: String, title: String) = """
        {"arcid":"$id","title":"$title","tags":"","isnew":"false",
         "extension":"zip","filename":"$id.zip","pagecount":10,
         "progress":0,"lastreadtime":0}
    """.trimIndent()

    private fun searchResultJson(archives: List<String>, total: Int): String {
        val data = archives.joinToString(",")
        return """{"data":[$data],"draw":1,"recordsFiltered":$total,"recordsTotal":$total}"""
    }

    // ═══════════════════════════════════════════════════════════
    // Multi-page sequential loading
    // ═══════════════════════════════════════════════════════════

    @Test
    fun multiPage_loadsThreePagesSequentially() = runTest {
        val source = createPagingSource()

        // Page 0: 2 items, total=5
        server.enqueue(MockResponse().setBody(searchResultJson(
            listOf(archiveJson("a1", "One"), archiveJson("a2", "Two")), total = 5
        )))
        // Page 1: 2 items
        server.enqueue(MockResponse().setBody(searchResultJson(
            listOf(archiveJson("a3", "Three"), archiveJson("a4", "Four")), total = 5
        )))
        // Page 2: 1 item (last page)
        server.enqueue(MockResponse().setBody(searchResultJson(
            listOf(archiveJson("a5", "Five")), total = 5
        )))

        // Load page 0
        val page0 = source.load(PagingSource.LoadParams.Refresh(null, 2, false))
        assertTrue(page0 is PagingSource.LoadResult.Page)
        val p0 = page0 as PagingSource.LoadResult.Page
        assertEquals(2, p0.data.size)
        assertEquals("One", p0.data[0].title)
        assertNull(p0.prevKey)
        assertNotNull(p0.nextKey)

        // Load page 1
        val page1 = source.load(PagingSource.LoadParams.Append(p0.nextKey!!, 2, false))
        assertTrue(page1 is PagingSource.LoadResult.Page)
        val p1 = page1 as PagingSource.LoadResult.Page
        assertEquals(2, p1.data.size)
        assertEquals("Three", p1.data[0].title)

        // Load page 2 (last)
        val page2 = source.load(PagingSource.LoadParams.Append(p1.nextKey!!, 2, false))
        assertTrue(page2 is PagingSource.LoadResult.Page)
        val p2 = page2 as PagingSource.LoadResult.Page
        assertEquals(1, p2.data.size)
        assertEquals("Five", p2.data[0].title)
        assertNull("Last page should have null nextKey", p2.nextKey)
    }

    // ═══════════════════════════════════════════════════════════
    // Search parameter verification across pages
    // ═══════════════════════════════════════════════════════════

    @Test
    fun combinedFilters_allParamsSentOnEveryPage() = runTest {
        val source = createPagingSource(
            filter = "artist:test",
            category = "manga",
            sortby = "title",
            order = "asc"
        )

        // Page 0
        server.enqueue(MockResponse().setBody(searchResultJson(
            listOf(archiveJson("x1", "X")), total = 2
        )))
        source.load(PagingSource.LoadParams.Refresh(null, 1, false))
        val req0 = server.takeRequest()
        val path0 = req0.path!!
        assertTrue("filter param missing", path0.contains("filter=artist"))
        assertTrue("category param missing", path0.contains("category=manga"))
        assertTrue("sortby param missing", path0.contains("sortby=title"))
        assertTrue("order param missing", path0.contains("order=asc"))
        assertFalse("start omitted when 0", path0.contains("start="))

        // Page 1
        server.enqueue(MockResponse().setBody(searchResultJson(
            listOf(archiveJson("x2", "Y")), total = 2
        )))
        source.load(PagingSource.LoadParams.Append(1, 1, false))
        val req1 = server.takeRequest()
        val path1 = req1.path!!
        assertTrue("start offset for page 1", path1.contains("start=1"))
        assertTrue("filter preserved on page 1", path1.contains("filter=artist"))
    }

    // ═══════════════════════════════════════════════════════════
    // Invalidation
    // ═══════════════════════════════════════════════════════════

    @Test
    fun invalidate_marksSourceAsInvalid() = runTest {
        val source = createPagingSource()

        assertFalse(source.invalid)
        source.invalidate()
        assertTrue(source.invalid)
    }

    @Test
    fun newSourceAfterInvalidation_startsFromPageZero() = runTest {
        // First source with filter "old"
        val source1 = createPagingSource(filter = "old")
        server.enqueue(MockResponse().setBody(searchResultJson(
            listOf(archiveJson("o1", "Old")), total = 1
        )))
        source1.load(PagingSource.LoadParams.Refresh(null, 3, false))
        val req1 = server.takeRequest()
        assertTrue(req1.path!!.contains("filter=old"))

        // Invalidate and create new source with different filter
        source1.invalidate()
        val source2 = createPagingSource(filter = "new")
        server.enqueue(MockResponse().setBody(searchResultJson(
            listOf(archiveJson("n1", "New")), total = 1
        )))
        val result = source2.load(PagingSource.LoadParams.Refresh(null, 3, false))
        val req2 = server.takeRequest()

        assertTrue(req2.path!!.contains("filter=new"))
        assertFalse("start omitted on fresh source", req2.path!!.contains("start="))
        val page = result as PagingSource.LoadResult.Page
        assertEquals("New", page.data[0].title)
    }

    // ═══════════════════════════════════════════════════════════
    // Edge cases
    // ═══════════════════════════════════════════════════════════

    @Test
    fun emptyFirstPage_returnsEmptyWithNoNextKey() = runTest {
        val source = createPagingSource(filter = "nonexistent")
        server.enqueue(MockResponse().setBody(searchResultJson(emptyList(), total = 0)))

        val result = source.load(PagingSource.LoadParams.Refresh(null, 3, false))
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertTrue(page.data.isEmpty())
        assertNull(page.nextKey)
    }

    @Test
    fun serverError_returnsLoadResultError() = runTest {
        val source = createPagingSource()
        server.enqueue(MockResponse().setResponseCode(500))

        val result = source.load(PagingSource.LoadParams.Refresh(null, 3, false))
        assertTrue("Should be Error on 500", result is PagingSource.LoadResult.Error)
    }

    @Test
    fun exactPageSizeBoundary_nextPageExists() = runTest {
        val source = createPagingSource()

        // Exactly 2 items returned with total=4 — next page should exist
        server.enqueue(MockResponse().setBody(searchResultJson(
            listOf(archiveJson("b1", "B1"), archiveJson("b2", "B2")), total = 4
        )))

        val result = source.load(PagingSource.LoadParams.Refresh(null, 2, false))
        val page = result as PagingSource.LoadResult.Page
        assertEquals(2, page.data.size)
        assertNotNull("Should have next page when items == pageSize and total > loaded", page.nextKey)
    }
}
