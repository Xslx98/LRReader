package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.download.DownloadState

/**
 * Pure decisions behind the tank detail page's members strip (spec
 * 2026-09-22 §4.6): the first [MAX_COVERS] members get a cover card, the
 * rest fold into one "+N" tail card; a member of a DOWNLOADED tank that is
 * not itself downloaded renders greyed (the downloads card's INCOMPLETE
 * state seen per member).
 */
object TankMemberStrip {

    const val MAX_COVERS = 8

    /** [shown] cover cards followed by a "+[more]" card when [more] > 0. */
    data class Plan(val shown: Int, val more: Int) {
        val hasMore: Boolean get() = more > 0
    }

    fun plan(memberCount: Int, maxCovers: Int = MAX_COVERS): Plan {
        val count = memberCount.coerceAtLeast(0)
        val shown = minOf(count, maxCovers.coerceAtLeast(0))
        return Plan(shown = shown, more = count - shown)
    }

    /**
     * Greyed = the tank is a downloaded group but this member has no
     * finished download (missing member). A tank that is not downloaded
     * never greys anything — online members are all reachable.
     */
    fun isGreyed(tankDownloaded: Boolean, memberState: DownloadState): Boolean =
        tankDownloaded && memberState != DownloadState.FINISH
}
