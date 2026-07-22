package com.hippo.ehviewer.stats

import com.hippo.ehviewer.dao.HistoryStatsRow
import com.lanraragi.reader.domain.Archive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tag-preference derivation (issue #19): each history archive contributes 1
 * count per tag (no weighting); the structural namespaces date_added /
 * timestamp / source / language are excluded; results group into top-5
 * artists, top-5 series+parody, top-10 namespace-less ("misc") tags.
 */
class TagPreferenceCalculatorTest {

    private fun rowWithTags(arcid: String, tags: Map<String, List<String>>) = HistoryStatsRow(
        arcid = arcid,
        serverProfileId = 1L,
        historyTime = 100L,
        archive = Archive(
            arcid = arcid, title = "t", tags = tags, pagecount = 10, progress = 1,
            extension = "zip", filename = "f.zip", thumbnailUrl = "", rating = 0f,
            isnew = false, lastreadtime = 0L, summary = null, serverProfileId = 1L,
        ),
    )

    @Test
    fun structuralNamespaces_neverAppear() {
        val stats = TagPreferenceCalculator.compute(
            listOf(
                rowWithTags(
                    "a".repeat(40),
                    mapOf(
                        "date_added" to listOf("2024-01-01"),
                        "timestamp" to listOf("123"),
                        "source" to listOf("site"),
                        "language" to listOf("chinese"),
                        "artist" to listOf("alice"),
                    )
                )
            )
        )

        assertEquals(listOf("alice"), stats.artists.map { it.tag })
        assertTrue(stats.misc.isEmpty())
        assertTrue(stats.series.isEmpty())
    }

    @Test
    fun countsOncePerArchive_groupsAndOrdersDeterministically() {
        val rows = listOf(
            rowWithTags("a".repeat(40), mapOf("artist" to listOf("alice"), "misc" to listOf("full color"))),
            rowWithTags("b".repeat(40), mapOf("artist" to listOf("alice", "bob"), "series" to listOf("touhou"))),
            rowWithTags("c".repeat(40), mapOf("parody" to listOf("touhou"), "misc" to listOf("full color"))),
        )

        val stats = TagPreferenceCalculator.compute(rows)

        assertEquals(listOf("alice" to 2, "bob" to 1), stats.artists.map { it.tag to it.count })
        // series + parody merge into one group
        assertEquals(listOf("touhou" to 2), stats.series.map { it.tag to it.count })
        assertEquals(listOf("full color" to 2), stats.misc.map { it.tag to it.count })
    }

    @Test
    fun topN_limitsApply() {
        val rows = (1..12).map { i ->
            rowWithTags(
                i.toString().padStart(40, '0'),
                mapOf("misc" to (1..i).map { "tag-$it" })
            )
        }

        val stats = TagPreferenceCalculator.compute(rows)

        assertEquals(10, stats.misc.size)
        // tag-1 appears in all 12 archives - it leads
        assertEquals("tag-1", stats.misc.first().tag)
        assertEquals(12, stats.misc.first().count)
    }

    @Test
    fun duplicateValuesWithinOneArchive_countOnce() {
        val stats = TagPreferenceCalculator.compute(
            listOf(rowWithTags("a".repeat(40), mapOf("artist" to listOf("alice", "alice"))))
        )
        assertEquals(listOf("alice" to 1), stats.artists.map { it.tag to it.count })
    }

    @Test
    fun emptyRows_produceEmptyGroups() {
        val stats = TagPreferenceCalculator.compute(emptyList())
        assertTrue(stats.artists.isEmpty() && stats.series.isEmpty() && stats.misc.isEmpty())
    }
}
