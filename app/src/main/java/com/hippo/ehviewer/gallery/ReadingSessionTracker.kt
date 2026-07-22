package com.hippo.ehviewer.gallery

/**
 * Per-reader-instance session bookkeeping, kept pure so the exactly-once and
 * baseline-advance semantics are JVM-testable (the GL-backed reader activity
 * itself cannot run under Robolectric).
 *
 * Lifecycle contract with the reader activity:
 * - [start] once the archive and its established start position are known.
 *   DIR-mode reading (no Archive) never calls start, so no events fire.
 * - [stop] from the activity's onStop with the current page. Each stop emits
 *   exactly one event, then advances the baseline to [endPage] so a
 *   stop→resume→stop cycle in the same instance reports only the new stretch
 *   and consumers never double-count pages.
 *
 * Deltas are approximate by design: user-initiated jumps (slider, thumbnail
 * grid) count as read pages, and a late async progress reconcile after
 * [start] can inflate one leg. Accepted at triage — page counts feed
 * an explicitly approximate statistic.
 */
class ReadingSessionTracker(
    private val notify: (ReadingSessionEnd) -> Unit = ReadingSessionEvents::notifySessionEnd,
) {

    private var arcid: String? = null
    private var serverProfileId: Long = 0L
    private var baselinePage: Int = 0
    private var pageCount: Int = 0

    fun start(arcid: String, serverProfileId: Long, startPage: Int, pageCount: Int) {
        this.arcid = arcid
        this.serverProfileId = serverProfileId
        this.baselinePage = startPage
        this.pageCount = pageCount
    }

    fun stop(endPage: Int) {
        val id = arcid ?: return
        notify(ReadingSessionEnd(id, serverProfileId, baselinePage, endPage, pageCount))
        baselinePage = endPage
    }
}
