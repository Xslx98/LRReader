package com.lanraragi.reader.ui.scene

import kotlin.math.max
import kotlin.math.min

/**
 * Pure range arithmetic for drag-to-select (spec 2026-09-22-drag-select §3/§4).
 *
 * The checked set during a drag is `preDrag ∪ range(anchor, cursor)` with
 * unselectable positions skipped: rows checked before the drag are never
 * un-checked by it (Google Photos semantics), and sliding the cursor back
 * toward the anchor un-checks only rows the drag itself added.
 */
object DragSelectRange {

    /**
     * @param anchor adapter position that was long-pressed
     * @param cursor adapter position currently under the finger
     * @param preDrag positions checked before the drag started
     * @param itemCount adapter size; positions outside `0 until itemCount` are ignored
     * @param canSelect rows the host refuses to check (e.g. tank pseudo-rows)
     * @return the complete checked set for this cursor
     */
    fun apply(
        anchor: Int,
        cursor: Int,
        preDrag: Set<Int>,
        itemCount: Int,
        canSelect: (Int) -> Boolean,
    ): Set<Int> {
        val result = LinkedHashSet(preDrag)
        if (itemCount <= 0) return result
        val from = max(0, min(anchor, cursor))
        val to = min(itemCount - 1, max(anchor, cursor))
        for (p in from..to) {
            if (canSelect(p)) result += p
        }
        return result
    }

    /** Positions to check and to un-check to move [current] to [target]. */
    fun diff(current: Set<Int>, target: Set<Int>): Diff =
        Diff(check = (target - current).sorted(), uncheck = (current - target).sorted())

    data class Diff(val check: List<Int>, val uncheck: List<Int>)
}
