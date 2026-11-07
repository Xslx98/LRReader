package com.lanraragi.reader.contract

import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.reading.ChapterIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapters a reader shows: `toc` from `/metadata` and from a tank's `/full`.
 * The client's decoder answers empty on a shape it does not know, so a non-empty
 * read of seeded chapters is what catches a format change here.
 */
class ChapterContractTest {

    private val client get() = ContractServer.client
    private val url get() = ContractServer.baseUrl
    private val seed get() = ContractServer.seed

    @Test
    fun metadata_readsSeededChaptersInPageOrder_withOneBasedPages() = contractTest {
        listOf(seed.big, seed.alpha).forEach { archive ->
            val toc = LRRArchiveApi.getArchiveMetadata(client, url, archive.id).toc

            assertTrue("${archive.title}: seeded chapters decoded to nothing", toc.isNotEmpty())
            assertTrue(archive.title, toc.all { it.page in 1..archive.pages })
            // Exact: in page order though added last page first, and the chapter
            // added at the first page reads back as page 1 with its name.
            assertEquals(
                archive.toc.sortedBy { it.page }.map { it.page to it.name },
                toc.map { it.page to it.name },
            )
        }
    }

    @Test
    fun metadata_ofAnArchiveWithoutChapters_isEmpty() = contractTest {
        assertEquals(emptyList<Any>(), seed.charlie.toc)

        assertEquals(emptyList<Any>(), LRRArchiveApi.getArchiveMetadata(client, url, seed.charlie.id).toc)
    }

    @Test
    fun tankFull_carriesEachMembersChapters() = contractTest {
        val tocs = LRRTankoubonApi.getTankoubonFull(client, url, seed.tank.id)
            .result.fullData.associate { it.arcid to it.toc }

        assertEquals(seed.tank.members.toSet(), tocs.keys)
        assertTrue(tocs.getValue(seed.alpha.id).isNotEmpty())
        assertEquals(LRRArchiveApi.getArchiveMetadata(client, url, seed.alpha.id).toc, tocs.getValue(seed.alpha.id))
        assertEquals(emptyList<Any>(), tocs.getValue(seed.bravo.id))
    }

    @Test
    fun chapterIndex_keepsEverySeededEntry_andMapsTheFirstPageToPageZero() = contractTest {
        val toc = LRRArchiveApi.getArchiveMetadata(client, url, seed.big.id).toc
        val realCount = LRRArchiveApi.getFileList(client, url, seed.big.id).size

        val chapters = ChapterIndex.fromToc(toc, realCount)

        assertEquals(seed.big.toc.size, chapters.size)
        assertEquals(0, chapters.first().page0)
        assertEquals(seed.big.toc.minBy { it.page }.name, chapters.first().title)
    }
}
