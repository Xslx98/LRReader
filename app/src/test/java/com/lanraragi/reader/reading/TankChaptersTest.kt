package com.lanraragi.reader.reading

import com.lanraragi.reader.client.api.data.LRRTocEntry
import com.lanraragi.reader.domain.ReaderChapter
import com.lanraragi.reader.domain.ReaderChapterList
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TankChaptersTest {

    private val seeds = listOf(TankMemberSeed("a", "A", 10), TankMemberSeed("b", "B", 20))
    private val map = TankPageMap(seeds.map { it.pagecount })
    private val scope = TestScope(UnconfinedTestDispatcher())

    private var fetches = 0
    private var nextFetch: suspend () -> Map<String, List<LRRTocEntry>> = { emptyMap() }
    private var published = ReaderChapterList()
    private var kept: Map<String, List<LRRTocEntry>> = emptyMap()
    private var storeReads = 0

    private val chapters = TankChapters(
        snapshot = { ChapterIndex.forTank(seeds, map, it) },
        stored = {
            storeReads++
            kept
        },
        fetch = {
            fetches++
            nextFetch()
        },
        publish = { published = it },
    )

    private val memberRows = listOf(ReaderChapter("A", 0), ReaderChapter("B", 10))

    @Test
    fun `members are listed without any fetch and the entry point is always offered`() {
        chapters.refresh()
        assertEquals(memberRows, published.entries)
        assertTrue(published.showEntryPoint)
        assertEquals(0, fetches)
    }

    @Test
    fun `the entry point stays offered before any member has a page count`() {
        val uncounted = TankChapters(
            snapshot = { ChapterIndex.forTank(seeds, TankPageMap(listOf(0, 0)), it) },
            stored = { emptyMap() },
            fetch = { emptyMap() },
            publish = { published = it },
        )
        uncounted.refresh()
        assertEquals(emptyList<ReaderChapter>(), published.entries)
        assertTrue(published.showEntryPoint)
    }

    @Test
    fun `opening the list shows loading, then nests the fetched chapters`() {
        val gate = CompletableDeferred<Map<String, List<LRRTocEntry>>>()
        nextFetch = { gate.await() }

        chapters.request(scope)
        assertTrue(published.loading)
        assertEquals(memberRows, published.entries)

        gate.complete(mapOf("b" to listOf(LRRTocEntry(3, "b3"))))
        scope.runCurrent()
        assertFalse(published.loading)
        assertFalse(published.failed)
        assertEquals(memberRows + ReaderChapter("b3", 12, depth = 1), published.entries)
    }

    @Test
    fun `a loaded list is never fetched again and a running fetch is not doubled`() {
        val gate = CompletableDeferred<Map<String, List<LRRTocEntry>>>()
        nextFetch = { gate.await() }
        chapters.request(scope)
        chapters.request(scope)
        assertEquals(1, fetches)

        gate.complete(emptyMap())
        scope.runCurrent()
        chapters.request(scope)
        assertEquals(1, fetches)
    }

    @Test
    fun `a failure keeps the members, reports it, and reopening retries`() {
        nextFetch = { throw IOException("offline") }
        chapters.request(scope)
        scope.runCurrent()
        assertTrue(published.failed)
        assertFalse(published.loading)
        assertEquals(memberRows, published.entries)

        nextFetch = { mapOf("a" to listOf(LRRTocEntry(2, "a2"))) }
        chapters.request(scope)
        scope.runCurrent()
        assertEquals(2, fetches)
        assertFalse(published.failed)
        assertEquals(ReaderChapter("a2", 1, depth = 1), published.entries[1])
    }

    @Test
    fun `offline, the kept chapters show and the failed request is not reported`() {
        kept = mapOf("b" to listOf(LRRTocEntry(3, "kept")), "a" to emptyList())
        nextFetch = { throw IOException("offline") }

        chapters.request(scope)
        scope.runCurrent()

        assertFalse(published.failed)
        assertFalse(published.loading)
        assertEquals(memberRows + ReaderChapter("kept", 12, depth = 1), published.entries)
    }

    @Test
    fun `kept chapters show while loading and the server's answer replaces them`() {
        kept = mapOf("a" to listOf(LRRTocEntry(1, "old")), "b" to listOf(LRRTocEntry(1, "removed upstream")))
        val gate = CompletableDeferred<Map<String, List<LRRTocEntry>>>()
        nextFetch = { gate.await() }

        chapters.request(scope)
        assertTrue(published.loading)
        assertEquals(ReaderChapter("old", 0, depth = 1), published.entries[1])

        gate.complete(mapOf("a" to listOf(LRRTocEntry(2, "edited")), "b" to emptyList()))
        scope.runCurrent()
        assertEquals(
            listOf(
                ReaderChapter("A", 0),
                ReaderChapter("edited", 1, depth = 1),
                ReaderChapter("B", 10),
            ),
            published.entries,
        )
    }

    @Test
    fun `a session that never opens the list does not read the store`() {
        kept = mapOf("a" to listOf(LRRTocEntry(1, "kept")))

        chapters.refresh()

        assertEquals(0, storeReads)
        assertEquals(memberRows, published.entries)
    }

    @Test
    fun `refresh after a remap moves fetched chapters to their new global pages`() {
        nextFetch = { mapOf("b" to listOf(LRRTocEntry(1, "b1"))) }
        chapters.request(scope)
        scope.runCurrent()
        assertEquals(10, published.entries.last().page0)

        map.correct(0, 4)
        chapters.refresh()
        assertEquals(4, published.entries.last().page0)
    }
}
