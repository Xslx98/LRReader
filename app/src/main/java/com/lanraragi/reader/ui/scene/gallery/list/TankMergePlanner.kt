package com.lanraragi.reader.ui.scene.gallery.list

import kotlin.math.abs

/**
 * Pure planner for the "merge into tankoubon" list choreography (spec
 * 2026-09-22 §4/§5). Turns the list's current shape (loaded arcids in order,
 * the visible position window) plus a batch outcome into the concrete
 * positions the view layer animates: which rows fly, how many are folded into
 * the "+N" pill, which rows get removed, where a provisional tank row goes
 * and what the flyers land on.
 *
 * Positions are PRE-removal adapter positions. The view layer resolves the
 * destination holder before it applies [MergePlan.removals] (descending, so
 * each removal leaves the smaller indices untouched) and tracks that view
 * live while later rows slide up.
 */
internal object TankMergePlanner {

    /** Cap on individual cover flyers; the rest ride the "+N" pill. */
    const val MAX_FLYERS = 8

    /** A loaded-but-off-screen tank row further than this many screens away is not scrolled to. */
    const val MAX_SCROLL_SCREENS = 3

    sealed interface Destination {
        /**
         * The tank's own row is loaded. [needsScroll] = it lies outside the
         * visible window (but within [MAX_SCROLL_SCREENS]) and must be
         * scrolled into view before the flyers can land on it.
         */
        data class Row(val position: Int, val needsScroll: Boolean) : Destination

        /** The provisional row inserted at [MergePlan.provisionalInsertAt]. */
        data object ProvisionalRow : Destination

        /** No tank row to land on: fly to the batch card's Tank button. */
        data object BatchButton : Destination
    }

    data class MergePlan(
        /** Pre-removal positions of succeeded rows that fly, top to bottom, at most [MAX_FLYERS]. */
        val flyerPositions: List<Int>,
        /** Succeeded archives with no flyer of their own (off screen, beyond the cap, or not loaded). */
        val extraCount: Int,
        /** Pre-removal positions to remove, DESCENDING. Empty when the list keeps its members (group mode off). */
        val removals: List<Int>,
        /** Post-removal index for the provisional tank row, or null when none is inserted. */
        val provisionalInsertAt: Int?,
        val destination: Destination,
    ) {
        val isNoOp: Boolean
            get() = flyerPositions.isEmpty() && extraCount == 0 &&
                removals.isEmpty() && provisionalInsertAt == null
    }

    /**
     * @param loadedIds every arcid in the data helper, in adapter order
     * @param firstVisible first attached adapter position, or -1 when nothing is laid out
     * @param lastVisible last attached adapter position (inclusive), or -1
     * @param succeeded arcids the batch added to the tank
     * @param tankId the tank's `TANK_…` id
     * @param groupMode `groupby_tanks` search mode: members leave the list and
     *   the tank row (real or provisional) is the destination
     */
    @Suppress("LongParameterList")
    fun plan(
        loadedIds: List<String>,
        firstVisible: Int,
        lastVisible: Int,
        succeeded: Collection<String>,
        tankId: String,
        groupMode: Boolean,
    ): MergePlan {
        val succeededSet = succeeded.toSet()
        val succeededPositions = loadedIds.indices.filter { loadedIds[it] in succeededSet }
        val hasWindow = firstVisible in 0..lastVisible
        val visible = if (hasWindow) {
            succeededPositions.filter { it in firstVisible..lastVisible }
        } else {
            emptyList()
        }
        val flyers = visible.take(MAX_FLYERS)
        val extra = succeededSet.size - flyers.size

        val removals = if (groupMode) succeededPositions.asReversed() else emptyList()

        val tankPosition = loadedIds.indexOf(tankId)
        val destination: Destination
        var insertAt: Int? = null
        when {
            tankPosition >= 0 -> {
                destination = rowDestination(tankPosition, firstVisible, lastVisible, hasWindow)
            }
            groupMode && succeededPositions.isNotEmpty() -> {
                // The topmost member's index survives the descending removals
                // unchanged: nothing above it is removed.
                insertAt = succeededPositions.first()
                destination = Destination.ProvisionalRow
            }
            else -> destination = Destination.BatchButton
        }

        return MergePlan(
            flyerPositions = flyers,
            extraCount = extra,
            removals = removals,
            provisionalInsertAt = insertAt,
            destination = destination,
        )
    }

    private fun rowDestination(
        tankPosition: Int,
        firstVisible: Int,
        lastVisible: Int,
        hasWindow: Boolean,
    ): Destination {
        if (!hasWindow || tankPosition in firstVisible..lastVisible) {
            return Destination.Row(tankPosition, needsScroll = false)
        }
        val screenRows = lastVisible - firstVisible + 1
        val distance = if (tankPosition < firstVisible) {
            firstVisible - tankPosition
        } else {
            tankPosition - lastVisible
        }
        return if (abs(distance) <= MAX_SCROLL_SCREENS * screenRows) {
            Destination.Row(tankPosition, needsScroll = true)
        } else {
            Destination.BatchButton
        }
    }
}
