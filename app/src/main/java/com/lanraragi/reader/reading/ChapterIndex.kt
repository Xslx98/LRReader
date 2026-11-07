package com.lanraragi.reader.reading

import com.lanraragi.reader.client.api.data.LRRTocEntry
import com.lanraragi.reader.domain.ReaderChapter

/**
 * Maps the server's table of contents to reader pages. Server pages are
 * 1-BASED and unchecked upstream, so anything outside the real page count is
 * dropped here; a blank name stays blank for the UI's fallback title.
 */
object ChapterIndex {

    /** The chapters of one archive with [pageCount] real pages, in page order. */
    fun fromToc(toc: List<LRRTocEntry>, pageCount: Int): List<ReaderChapter> =
        toc.asSequence()
            .filter { it.page in 1..pageCount }
            .sortedBy { it.page }
            .map { ReaderChapter(title = it.name.trim(), page0 = it.page - 1) }
            .toList()

    /**
     * A tank's two-level list: each member, then its chapters. Offsets come
     * from the live [map], so call this again after every remap. [members]
     * must be index-aligned with [map]. A member with no known pages has
     * nothing to jump to and stays out until a correction gives it a count.
     */
    fun forTank(
        members: List<TankMemberSeed>,
        map: TankPageMap,
        tocs: Map<String, List<LRRTocEntry>>,
    ): List<ReaderChapter> = buildList {
        members.forEachIndexed { index, member ->
            val count = map.pageCountOf(index)
            if (count <= 0) return@forEachIndexed
            val start = map.memberStart(index)
            add(ReaderChapter(title = member.title, page0 = start))
            fromToc(tocs[member.arcid].orEmpty(), count).forEach { chapter ->
                add(ReaderChapter(title = chapter.title, page0 = start + chapter.page0, depth = 1))
            }
        }
    }

    /**
     * Row of the chapter holding [page0] in a page-ordered list, or -1 before
     * the first chapter. On a shared page the later row wins: the chapter
     * starting a member, not the member itself.
     */
    fun currentIndex(chapters: List<ReaderChapter>, page0: Int): Int =
        chapters.indexOfLast { it.page0 <= page0 }
}
