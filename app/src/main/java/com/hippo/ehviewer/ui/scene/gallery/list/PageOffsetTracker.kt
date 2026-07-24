package com.hippo.ehviewer.ui.scene.gallery.list

/**
 * Maps ContentLayout's dense page indexes (0,1,2,…) to raw server offsets.
 *
 * /api/search returns `archives_per_page` rows per request regardless of the
 * client's nominal page size, so page N's true start offset is only known
 * once page N-1 has loaded (audit NET-2). ContentLayout only ever requests
 * pages sequentially (refresh resets to 0, then mEndPage increments), so
 * chaining each load's reported next offset is sufficient.
 *
 * Not thread-safe: call only from the main thread (ContentLayout's own
 * threading model).
 */
class PageOffsetTracker(private val nominalPageSize: Int) {

    private val offsets = HashMap<Int, Int>()

    /**
     * Offset to request for a dense [page] index. Pages never chained (e.g.
     * a state restore mid-list) fall back to the nominal fixed-size math.
     */
    fun offsetFor(page: Int): Int = offsets[page] ?: (page * nominalPageSize)

    /**
     * Record that [page] finished loading and the server-side offset of the
     * first unseen row is [nextOffset] (null = no more rows, nothing to chain).
     */
    fun recordLoaded(page: Int, nextOffset: Int?) {
        if (nextOffset != null) {
            offsets[page + 1] = nextOffset
        }
    }

    /** Forget all chained offsets (call when a refresh restarts from page 0). */
    fun reset() {
        offsets.clear()
    }
}
