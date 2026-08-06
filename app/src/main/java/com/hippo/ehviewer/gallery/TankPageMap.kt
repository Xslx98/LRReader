package com.hippo.ehviewer.gallery

/**
 * Mutable global ↔ (member, local) page mapping for the tank composite
 * reader ([TankGalleryProvider]). All pages are READER 0-indexed here —
 * 1-indexed conversion happens only at server API boundaries.
 *
 * Built from member `pagecount` METADATA (which can disagree with the real
 * file list, or go stale when a member is deleted server-side), so the map
 * is correctable in place: [correct] when a member's actual file list
 * lands, [removeMember] when a member is confirmed deleted. Callers own
 * re-notifying the GL layer after any mutation.
 *
 * Thread-safety: mutations and reads are synchronized on the instance —
 * they are rare (per member, not per page) and the reader calls [locate]
 * from GL/main/IO threads.
 */
class TankPageMap(pagecounts: List<Int>) {

    private var counts: MutableList<Int> =
        pagecounts.mapTo(ArrayList(pagecounts.size)) { it.coerceAtLeast(0) }

    /** offsets[i] = first global page of member i; offsets[size] = total. */
    private var offsets: List<Int> = TankPageMath.pageOffsets(counts)

    val total: Int
        @Synchronized get() = offsets.last()

    val memberCount: Int
        @Synchronized get() = counts.size

    @Synchronized
    fun pageCountOf(memberIndex: Int): Int = counts[memberIndex]

    /** Global 0-indexed first page of [memberIndex]. */
    @Synchronized
    fun memberStart(memberIndex: Int): Int = offsets[memberIndex]

    /**
     * (memberIndex, local page0) for global 0-indexed [global0], skipping
     * zero-page members; null when out of range.
     */
    @Synchronized
    fun locate(global0: Int): Pair<Int, Int>? {
        if (global0 < 0 || global0 >= offsets.last()) return null
        // TankPageMath.locate works in 1-indexed clamped space; out-of-range
        // is already rejected above, so the clamp inside never fires.
        return TankPageMath.locate(offsets, global0 + 1)
    }

    /** Global 0-indexed page of ([memberIndex], local [page0]). */
    @Synchronized
    fun globalOf(memberIndex: Int, page0: Int): Int = offsets[memberIndex] + page0

    /**
     * Replace [memberIndex]'s metadata pagecount with the real file-list
     * count. Returns true when the mapping actually changed.
     */
    @Synchronized
    fun correct(memberIndex: Int, actualPageCount: Int): Boolean {
        val clamped = actualPageCount.coerceAtLeast(0)
        if (counts[memberIndex] == clamped) return false
        counts[memberIndex] = clamped
        offsets = TankPageMath.pageOffsets(counts)
        return true
    }

    /** Drop a confirmed-deleted member; later members shift down. */
    @Synchronized
    fun removeMember(memberIndex: Int) {
        counts.removeAt(memberIndex)
        offsets = TankPageMath.pageOffsets(counts)
    }
}
