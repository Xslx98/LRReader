package com.hippo.ehviewer.stats

import com.hippo.ehviewer.dao.HistoryStatsRow
import com.lanraragi.reader.domain.Archive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure snapshot-statistics derivation (issue #18): totals, completed count
 * (progress >= pagecount), clamped pages-read sum, per-server breakdown, and
 * the recently-completed list ordered by last activity — where legacy rows may
 * carry seconds OR milliseconds in `lastreadtime` and must not skew ordering
 * (normalizeEpochSeconds at the math entry).
 */
class ReadingStatsCalculatorTest {

    private fun archive(
        arcid: String,
        profileId: Long,
        title: String = "t-$arcid",
        pagecount: Int = 10,
        progress: Int = 0,
        lastreadtime: Long = 0L,
    ) = Archive(
        arcid = arcid, title = title, tags = emptyMap(), pagecount = pagecount,
        progress = progress, extension = "zip", filename = "f.zip", thumbnailUrl = "",
        rating = 0f, isnew = false, lastreadtime = lastreadtime, summary = null,
        serverProfileId = profileId,
    )

    private fun row(
        archive: Archive?,
        historyTime: Long? = 1_000L,
        arcid: String = archive?.arcid ?: "x".repeat(40),
        profileId: Long = archive?.serverProfileId ?: 1L,
    ) = HistoryStatsRow(arcid, profileId, historyTime, archive)

    @Test
    fun totals_completedAndClampedPages() {
        val rows = listOf(
            row(archive("a".repeat(40), 1L, pagecount = 10, progress = 10)),   // completed
            row(archive("b".repeat(40), 1L, pagecount = 20, progress = 5)),    // partial
            row(archive("c".repeat(40), 1L, pagecount = 10, progress = 15)),   // over-progress clamps
            row(null, arcid = "d".repeat(40), profileId = 1L),                 // broken json row
        )

        val stats = ReadingStatsCalculator.compute(rows, emptyMap())

        assertEquals(4, stats.totalArchives)
        assertEquals(2, stats.completedCount)
        assertEquals((10 + 5 + 10).toLong(), stats.totalPagesRead)
    }

    @Test
    fun perServer_groupsAndResolvesNames() {
        val rows = listOf(
            row(archive("a".repeat(40), 1L, pagecount = 10, progress = 10)),
            row(archive("b".repeat(40), 1L, pagecount = 10, progress = 2)),
            row(archive("c".repeat(40), 2L, pagecount = 10, progress = 10)),
        )

        val stats = ReadingStatsCalculator.compute(rows, mapOf(1L to "Home"))

        assertEquals(2, stats.perServer.size)
        val s1 = stats.perServer.first { it.profileId == 1L }
        assertEquals("Home", s1.serverName)
        assertEquals(2, s1.archives)
        assertEquals(1, s1.completed)
        assertEquals(12L, s1.pagesRead)
        assertNull(stats.perServer.first { it.profileId == 2L }.serverName)
    }

    @Test
    fun recentlyCompleted_ordersByActivity_completedOnly_limited() {
        val rows = listOf(
            row(archive("a".repeat(40), 1L, title = "old", pagecount = 5, progress = 5), historyTime = 100L),
            row(archive("b".repeat(40), 1L, title = "new", pagecount = 5, progress = 5), historyTime = 200L),
            row(archive("c".repeat(40), 1L, title = "partial", pagecount = 5, progress = 1), historyTime = 300L),
        )

        val stats = ReadingStatsCalculator.compute(rows, emptyMap(), recentLimit = 1)

        assertEquals(listOf("new"), stats.recentlyCompleted.map { it.title })
    }

    @Test
    fun mixedEpochUnits_inLastreadtimeFallback_doNotSkewOrdering() {
        // No historyTime -> falls back to archive.lastreadtime, where legacy
        // rows may hold ms. A(seconds, later real time) must outrank B(ms,
        // earlier real time) even though B's raw number is 1000x larger.
        val laterSeconds = 2_000_000_000L
        val earlierMillis = 1_700_000_000_000L
        val rows = listOf(
            row(
                archive("a".repeat(40), 1L, title = "later", pagecount = 5, progress = 5, lastreadtime = laterSeconds),
                historyTime = null,
            ),
            row(
                archive("b".repeat(40), 1L, title = "earlier", pagecount = 5, progress = 5, lastreadtime = earlierMillis),
                historyTime = null,
            ),
        )

        val stats = ReadingStatsCalculator.compute(rows, emptyMap())

        assertEquals(listOf("later", "earlier"), stats.recentlyCompleted.map { it.title })
    }

    @Test
    fun emptyRows_produceEmptyStats() {
        val stats = ReadingStatsCalculator.compute(emptyList(), emptyMap())
        assertEquals(0, stats.totalArchives)
        assertEquals(0, stats.completedCount)
        assertEquals(0L, stats.totalPagesRead)
        assertEquals(0, stats.perServer.size)
        assertEquals(0, stats.recentlyCompleted.size)
    }
}
