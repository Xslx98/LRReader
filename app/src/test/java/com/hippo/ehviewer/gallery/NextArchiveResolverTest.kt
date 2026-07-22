package com.hippo.ehviewer.gallery

import com.lanraragi.reader.domain.Archive
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

class NextArchiveResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var client: OkHttpClient
    private lateinit var resolver: NextArchiveResolver

    @Before
    fun setUp() {
        ReadingContextStore.clear()
        server = MockWebServer()
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        resolver = NextArchiveResolver(client)
    }

    @After
    fun tearDown() {
        try { server.shutdown() } catch (ignored: Exception) { }
    }

    private fun id(c: Char) = c.toString().repeat(40)

    private fun searchBody(vararg arcids: String, filtered: Int = arcids.size): String {
        val items = arcids.joinToString(",") {
            """{"arcid":"$it","title":"t-$it","tags":"","isnew":"false","extension":"zip","filename":"f","pagecount":10,"progress":0,"lastreadtime":0}"""
        }
        return """{"data":[$items],"draw":0,"recordsFiltered":$filtered,"recordsTotal":$filtered}"""
    }

    private fun onlineCtx(anchor: String, index: Int) = ReadingContext.OnlineSearch(
        sourceProfileId = 7L, sourceBaseUrl = baseUrl,
        filter = null, category = null, sortby = "title", order = "asc",
        newonly = false, untaggedonly = false,
        anchorArcid = anchor, anchorIndex = index,
    )

    private fun localArchive(arcid: String) = Archive(
        arcid = arcid, title = "t", tags = emptyMap(), pagecount = 5, progress = 0,
        extension = "zip", filename = "f", thumbnailUrl = "", rating = 0f,
        isnew = false, lastreadtime = 0L, summary = null, serverProfileId = 3L,
    )

    @Test
    fun `no context published resolves NoContext`() = runTest {
        assertEquals(NextArchiveResolver.NextResult.NoContext, resolver.resolve(id('a')))
    }

    @Test
    fun `context anchored on another arcid resolves NoContext`() = runTest {
        ReadingContextStore.publish(onlineCtx(id('a'), 0))
        assertEquals(NextArchiveResolver.NextResult.NoContext, resolver.resolve(id('z')))
    }

    @Test
    fun `online window fetch never sends hidecompleted`() = runTest {
        // Contract lock (decided at triage of the hide-completed filter): the
        // resolver re-runs the original search to re-locate the just-read anchor.
        // If hidecompleted rode along, a just-completed anchor would vanish from
        // the window and the chain would break. Never plumb the toggle in here.
        server.enqueue(MockResponse().setBody(searchBody(id('a'), id('b'), filtered = 10)))
        ReadingContextStore.publish(onlineCtx(id('a'), 0))

        resolver.resolve(id('a'))

        assertFalse(server.takeRequest().path!!.contains("hidecompleted"))
    }

    @Test
    fun `online happy path - anchor found in window, next is following entry`() = runTest {
        server.enqueue(MockResponse().setBody(searchBody(id('a'), id('b'), id('c'), filtered = 10)))
        ReadingContextStore.publish(onlineCtx(id('a'), 0))
        val r = resolver.resolve(id('a')) as NextArchiveResolver.NextResult.Next
        assertEquals(id('b'), r.archive.arcid)
        assertEquals(7L, r.archive.serverProfileId)
        assertTrue(r.archive.thumbnailUrl.startsWith(baseUrl))
        assertEquals(id('b'), (r.advanced as ReadingContext.OnlineSearch).anchorArcid)
    }

    @Test
    fun `online anchor at window end - second fetch returns next`() = runTest {
        server.enqueue(MockResponse().setBody(searchBody(id('a'), filtered = 10)))
        server.enqueue(MockResponse().setBody(searchBody(id('b'), filtered = 10)))
        ReadingContextStore.publish(onlineCtx(id('a'), 4))
        val r = resolver.resolve(id('a')) as NextArchiveResolver.NextResult.Next
        assertEquals(id('b'), r.archive.arcid)
    }

    @Test
    fun `online drift - anchor gone, entry now at old slot becomes next`() = runTest {
        // NOTE: arcids must be valid 40-char lowercase-hex (requireValidArcid, used
        // by toArchive -> getThumbnailUrl); 'x'/'y'/'v'/'w' aren't hex digits, so
        // this uses 'c'/'d'/'e'/'f' instead of the plan's literal letters.
        server.enqueue(MockResponse().setBody(searchBody(id('c'), id('d'), filtered = 10)))
        server.enqueue(MockResponse().setBody(searchBody(id('e'), id('f'), filtered = 10)))
        ReadingContextStore.publish(onlineCtx(id('a'), 5))
        val r = resolver.resolve(id('a')) as NextArchiveResolver.NextResult.Next
        assertEquals(id('c'), r.archive.arcid)
    }

    @Test
    fun `online tankoubon entries are skipped`() = runTest {
        server.enqueue(
            MockResponse().setBody(searchBody(id('a'), "TANK_1234567890", id('b'), filtered = 10))
        )
        ReadingContextStore.publish(onlineCtx(id('a'), 0))
        val r = resolver.resolve(id('a')) as NextArchiveResolver.NextResult.Next
        assertEquals(id('b'), r.archive.arcid)
    }

    @Test
    fun `online end of results resolves EndOfList`() = runTest {
        server.enqueue(MockResponse().setBody(searchBody(id('a'), filtered = 1)))
        ReadingContextStore.publish(onlineCtx(id('a'), 0))
        assertEquals(NextArchiveResolver.NextResult.EndOfList, resolver.resolve(id('a')))
    }

    @Test
    fun `online network failure resolves Error`() = runTest {
        server.shutdown()
        ReadingContextStore.publish(onlineCtx(id('a'), 0))
        assertTrue(resolver.resolve(id('a')) is NextArchiveResolver.NextResult.Error)
    }

    @Test
    fun `local list walks the snapshot and advances by dropping the consumed prefix`() = runTest {
        val a = localArchive(id('a')); val b = localArchive(id('b')); val c = localArchive(id('c'))
        ReadingContextStore.publish(
            ReadingContext.LocalList(ReadingContext.LocalList.Kind.HISTORY, listOf(a, b, c), id('a'))
        )
        val r = resolver.resolve(id('a')) as NextArchiveResolver.NextResult.Next
        assertEquals(id('b'), r.archive.arcid)
        val adv = r.advanced as ReadingContext.LocalList
        assertEquals(listOf(id('b'), id('c')), adv.forwardArchives.map { it.arcid })
    }

    @Test
    fun `local list exhausted resolves EndOfList`() = runTest {
        val a = localArchive(id('a'))
        ReadingContextStore.publish(
            ReadingContext.LocalList(ReadingContext.LocalList.Kind.DOWNLOADS, listOf(a), id('a'))
        )
        assertEquals(NextArchiveResolver.NextResult.EndOfList, resolver.resolve(id('a')))
    }
}
