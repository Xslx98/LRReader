package com.lanraragi.reader.ui.scene.download

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.lanraragi.reader.dao.DownloadInfo

/**
 * Turns a Downloads list change into adapter notifications on the main
 * thread without letting DiffUtil's cost run away (audit PERF-09 / C17).
 *
 * DiffUtil costs O((N + M) * D) for D edits. The everyday changes — a row
 * changing state, one download added or removed — are a handful of edits
 * and diff in well under a millisecond even at thousands of rows. A
 * whole-list rewrite (another label, another sort order) makes D ~ N: a
 * 5000-row list then took a quarter of a second on a desktop JVM, several
 * times that on a phone. So a long list whose change is estimated to be
 * large skips the diff and is replaced as two range notifications, the way
 * ContentLayout handles deep lists; such a change shows a different list
 * anyway, so losing per-row animations and the scroll anchor is expected.
 *
 * The diff stays on the main thread on purpose: the adapter reads the
 * view model's live list, so a diff dispatched later would leave the
 * adapter's item count changed with no notification in between.
 */
internal object DownloadListDiff {

    /** Lists up to this size are always diffed. */
    const val MAX_ALWAYS_DIFF = 200

    /** Past [MAX_ALWAYS_DIFF] rows, a change estimated above this many edits is replaced, not diffed. */
    const val MAX_DIFFED_EDITS = 64

    fun dispatch(old: List<DownloadInfo>, new: List<DownloadInfo>, target: ListUpdateCallback) {
        if (maxOf(old.size, new.size) > MAX_ALWAYS_DIFF && editEstimate(old, new) > MAX_DIFFED_EDITS) {
            if (old.isNotEmpty()) target.onRemoved(0, old.size)
            if (new.isNotEmpty()) target.onInserted(0, new.size)
            return
        }
        DiffUtil.calculateDiff(DownloadInfoDiffCallback(old, new)).dispatchUpdatesTo(target)
    }

    /**
     * An O(N + M) estimate of how many edits turn [old] into [new]: rows in
     * only one of the lists, plus the places where rows in both stop
     * following their old relative order (each needs at least one move).
     */
    fun editEstimate(old: List<DownloadInfo>, new: List<DownloadInfo>): Int {
        val oldIndex = HashMap<String?, Int>(old.size * 2)
        old.forEachIndexed { i, info -> oldIndex[info.arcid] = i }
        var onlyInOne = 0
        var shared = 0
        var orderBreaks = 0
        var lastOldIndex = -1
        for (info in new) {
            val i = oldIndex[info.arcid]
            if (i == null) {
                onlyInOne++
                continue
            }
            shared++
            if (i < lastOldIndex) orderBreaks++
            lastOldIndex = i
        }
        onlyInOne += old.size - shared
        return onlyInOne + orderBreaks
    }
}
