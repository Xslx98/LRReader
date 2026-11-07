package com.lanraragi.reader.reading

import com.lanraragi.reader.client.api.data.LRRTocEntry
import com.lanraragi.reader.domain.ReaderChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterIndexTest {

    private fun members(vararg arcids: String) = arcids.map { TankMemberSeed(it, "title-$it", 0) }

    // ── fromToc ──────────────────────────────────────────────────────

    @Test
    fun `server pages become 0-indexed reader pages`() {
        assertEquals(
            listOf(ReaderChapter("One", 0), ReaderChapter("Two", 12)),
            ChapterIndex.fromToc(listOf(LRRTocEntry(1, "One"), LRRTocEntry(13, "Two")), 20),
        )
    }

    @Test
    fun `pages outside the real count are dropped, the last page kept`() {
        val toc = listOf(
            LRRTocEntry(-1, "negative"),
            LRRTocEntry(0, "zero"),
            LRRTocEntry(10, "last"),
            LRRTocEntry(11, "past the end"),
        )
        assertEquals(listOf(ReaderChapter("last", 9)), ChapterIndex.fromToc(toc, 10))
    }

    @Test
    fun `the real page count decides, so an unknown count keeps nothing`() {
        assertEquals(emptyList<ReaderChapter>(), ChapterIndex.fromToc(listOf(LRRTocEntry(1, "One")), 0))
    }

    @Test
    fun `a blank name stays for the fallback and the entry is kept`() {
        assertEquals(listOf(ReaderChapter("", 1)), ChapterIndex.fromToc(listOf(LRRTocEntry(2, "   ")), 5))
    }

    @Test
    fun `entries are ordered by page whatever the input order`() {
        val toc = listOf(LRRTocEntry(9, "b"), LRRTocEntry(3, "a"))
        assertEquals(listOf(2, 8), ChapterIndex.fromToc(toc, 10).map { it.page0 })
    }

    // ── forTank ──────────────────────────────────────────────────────

    @Test
    fun `members are the outer level and their chapters nest with global pages`() {
        val chapters = ChapterIndex.forTank(
            members("a", "b"),
            TankPageMap(listOf(10, 20)),
            mapOf("b" to listOf(LRRTocEntry(1, "b1"), LRRTocEntry(5, "b5"))),
        )
        assertEquals(
            listOf(
                ReaderChapter("title-a", 0),
                ReaderChapter("title-b", 10),
                ReaderChapter("b1", 10, depth = 1),
                ReaderChapter("b5", 14, depth = 1),
            ),
            chapters,
        )
    }

    @Test
    fun `a correction moves later members and re-checks the corrected member's range`() {
        val map = TankPageMap(listOf(10, 20))
        val tocs = mapOf(
            "a" to listOf(LRRTocEntry(8, "a8")),
            "b" to listOf(LRRTocEntry(2, "b2")),
        )
        map.correct(0, 6)
        assertEquals(
            listOf(
                ReaderChapter("title-a", 0),
                ReaderChapter("title-b", 6),
                ReaderChapter("b2", 7, depth = 1),
            ),
            ChapterIndex.forTank(members("a", "b"), map, tocs),
        )
    }

    @Test
    fun `removing a member shifts the later ones down`() {
        val map = TankPageMap(listOf(10, 20, 5))
        val tocs = mapOf("c" to listOf(LRRTocEntry(3, "c3")))
        map.removeMember(1)
        assertEquals(
            listOf(
                ReaderChapter("title-a", 0),
                ReaderChapter("title-c", 10),
                ReaderChapter("c3", 12, depth = 1),
            ),
            ChapterIndex.forTank(members("a", "c"), map, tocs),
        )
    }

    @Test
    fun `seeds without page counts list a member only once it is counted`() {
        val map = TankPageMap(listOf(0, 0))
        val members = members("a", "b")
        assertEquals(emptyList<ReaderChapter>(), ChapterIndex.forTank(members, map, emptyMap()))
        map.correct(1, 4)
        assertEquals(listOf(ReaderChapter("title-b", 0)), ChapterIndex.forTank(members, map, emptyMap()))
    }

    // ── currentIndex ─────────────────────────────────────────────────

    @Test
    fun `the current chapter is the last one starting at or before the page`() {
        val chapters = listOf(ReaderChapter("a", 2), ReaderChapter("b", 5))
        assertEquals(-1, ChapterIndex.currentIndex(chapters, 1))
        assertEquals(0, ChapterIndex.currentIndex(chapters, 2))
        assertEquals(0, ChapterIndex.currentIndex(chapters, 4))
        assertEquals(1, ChapterIndex.currentIndex(chapters, 5))
        assertEquals(1, ChapterIndex.currentIndex(chapters, 99))
    }

    @Test
    fun `a chapter on a member's first page is current rather than the member`() {
        val chapters = listOf(ReaderChapter("member", 10), ReaderChapter("chapter", 10, depth = 1))
        assertEquals(1, ChapterIndex.currentIndex(chapters, 10))
    }
}
