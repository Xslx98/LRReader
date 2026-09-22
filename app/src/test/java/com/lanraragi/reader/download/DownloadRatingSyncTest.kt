package com.lanraragi.reader.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Cold-start rating sync strategy (audit 2026-09-22 A29). */
class DownloadRatingSyncTest {

    private val searches = AtomicInteger()
    private val metadataGets = AtomicInteger()

    private fun sync(
        up: Boolean = true,
        library: List<Pair<String, String>>,
        pageSize: Int = 2,
    ) = DownloadRatingSync(
        probe = { up },
        searchPage = { _, start ->
            searches.incrementAndGet()
            DownloadRatingSync.Page(library.drop(start).take(pageSize), library.size)
        },
        fetchTags = { _, arcid ->
            metadataGets.incrementAndGet()
            library.first { it.first == arcid }.second
        },
    )

    @Test
    fun unreachableServerIsSkippedWithoutFurtherRequests() = runTest {
        val result = sync(up = false, library = listOf("a" to "rating:⭐")).ratings("u", setOf("a"))
        assertNull(result)
        assertEquals(0, searches.get())
        assertEquals(0, metadataGets.get())
    }

    @Test
    fun smallLibraryIsReadFromTheSearchListing() = runTest {
        val library = listOf("a" to "rating:⭐⭐", "b" to "artist:x", "c" to "rating:⭐", "d" to "")
        val result = sync(library = library).ratings("u", setOf("a", "b", "c"))!!
        assertEquals(mapOf("a" to 2f, "b" to -1f, "c" to 1f), result)
        assertEquals(0, metadataGets.get())
        assertEquals(2, searches.get())
    }

    @Test
    fun largeLibraryFallsBackToPerArchiveRequests() = runTest {
        val library = (1..100).map { "id$it" to "rating:⭐⭐⭐" }
        val result = sync(library = library).ratings("u", setOf("id7", "id9"))!!
        assertEquals(mapOf("id7" to 3f, "id9" to 3f), result)
        assertEquals(1, searches.get())
        assertEquals(2, metadataGets.get())
    }

    @Test
    fun failingArchivesAreLeftOut() = runTest {
        val s = DownloadRatingSync(
            probe = { true },
            searchPage = { _, _ -> DownloadRatingSync.Page(emptyList(), 1000) },
            fetchTags = { _, arcid -> if (arcid == "bad") error("boom") else "rating:⭐" },
        )
        val result = s.ratings("u", setOf("ok", "bad"))!!
        assertEquals(mapOf("ok" to 1f), result)
        assertTrue("bad" !in result)
    }
}
