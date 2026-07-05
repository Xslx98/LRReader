package com.hippo.ehviewer.gallery

/**
 * Pure math for a Tankoubon's GLOBAL 1-indexed page numbering (LANraragi
 * spans page numbers across member archives in order: members of 20+25
 * pages → global page 26 is member #2, local page 6). Reader pages are
 * 0-indexed; conversion happens HERE and only here.
 */
object TankPageMath {

    /** offsets[i] = total pages of members 0..i-1; size = pagecounts.size + 1. */
    fun pageOffsets(pagecounts: List<Int>): List<Int> {
        val out = ArrayList<Int>(pagecounts.size + 1)
        var acc = 0
        out.add(0)
        for (p in pagecounts) {
            acc += p.coerceAtLeast(0)
            out.add(acc)
        }
        return out
    }

    /** Global 1-indexed page for [memberIndex]'s local 0-indexed [page0]. */
    fun globalPage1(offsets: List<Int>, memberIndex: Int, page0: Int): Int =
        offsets[memberIndex] + page0 + 1

    /**
     * (memberIndex, local page0) for a global 1-indexed page. Overflow clamps
     * to the very last page (a member may have been deleted server-side since
     * the progress was written); a global page never lands on a 0-page member.
     * Null when the page is not positive or the tank has no pages at all.
     */
    fun locate(offsets: List<Int>, globalPage1: Int): Pair<Int, Int>? {
        val total = offsets.last()
        if (globalPage1 < 1 || total <= 0) return null
        val page = globalPage1.coerceAtMost(total)
        // find the last member whose offset is below `page`
        var member = offsets.size - 2
        while (member > 0 && offsets[member] >= page) member--
        return member to (page - offsets[member] - 1)
    }
}
