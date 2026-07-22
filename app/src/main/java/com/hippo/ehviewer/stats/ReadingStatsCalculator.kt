package com.hippo.ehviewer.stats

import com.hippo.ehviewer.dao.HistoryStatsRow
import com.hippo.ehviewer.gallery.ReadingProgressReconciler

/**
 * Pure derivation of the snapshot reading statistics (issue #18) from the
 * cross-profile history rows. All timestamp math funnels through
 * [ReadingProgressReconciler.normalizeEpochSeconds] — legacy `lastreadtime`
 * values may still hold milliseconds where seconds are expected.
 *
 * "Completed" per CONTEXT.md: progress has reached the page count. Pages-read
 * is an explicitly approximate proxy: the sum of current progress, clamped to
 * [0, pagecount] per archive.
 */
object ReadingStatsCalculator {

    data class ServerBreakdown(
        val profileId: Long,
        /** Resolved profile name, or null when the profile no longer exists. */
        val serverName: String?,
        val archives: Int,
        val completed: Int,
        val pagesRead: Long,
    )

    data class CompletedEntry(
        val title: String,
        val arcid: String,
        val serverProfileId: Long,
        val lastActivityMs: Long,
    )

    data class ReadingStats(
        val totalArchives: Int,
        val completedCount: Int,
        val totalPagesRead: Long,
        val perServer: List<ServerBreakdown>,
        val recentlyCompleted: List<CompletedEntry>,
    )

    fun compute(
        rows: List<HistoryStatsRow>,
        profileNames: Map<Long, String>,
        recentLimit: Int = RECENT_LIMIT,
    ): ReadingStats {
        val completedRows = rows.filter { it.archive.isCompleted() }
        val perServer = rows.groupBy { it.serverProfileId }.map { (profileId, group) ->
            ServerBreakdown(
                profileId = profileId,
                serverName = profileNames[profileId],
                archives = group.size,
                completed = group.count { it.archive.isCompleted() },
                pagesRead = group.sumOf { it.archive.pagesRead() },
            )
        }.sortedByDescending { it.archives }

        val recentlyCompleted = completedRows
            .mapNotNull { row ->
                val archive = row.archive ?: return@mapNotNull null
                CompletedEntry(
                    title = archive.title,
                    arcid = row.arcid,
                    serverProfileId = row.serverProfileId,
                    lastActivityMs = row.lastActivityMs(),
                )
            }
            .sortedByDescending { it.lastActivityMs }
            .take(recentLimit)

        return ReadingStats(
            totalArchives = rows.size,
            completedCount = completedRows.size,
            totalPagesRead = rows.sumOf { it.archive.pagesRead() },
            perServer = perServer,
            recentlyCompleted = recentlyCompleted,
        )
    }

    private fun com.lanraragi.reader.domain.Archive?.isCompleted(): Boolean =
        this != null && pagecount > 0 && progress >= pagecount

    private fun com.lanraragi.reader.domain.Archive?.pagesRead(): Long =
        if (this == null) 0L else progress.coerceIn(0, pagecount.coerceAtLeast(0)).toLong()

    /** HISTORY_TIME is canonical ms; the lastreadtime fallback is unit-normalized. */
    private fun HistoryStatsRow.lastActivityMs(): Long =
        historyTime
            ?: archive?.lastreadtime?.let {
                ReadingProgressReconciler.normalizeEpochSeconds(it) * MS_PER_SECOND
            }
            ?: 0L

    private const val RECENT_LIMIT = 10
    private const val MS_PER_SECOND = 1000L
}
