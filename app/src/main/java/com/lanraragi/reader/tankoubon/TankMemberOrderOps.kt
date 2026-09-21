package com.lanraragi.reader.tankoubon

/**
 * Pure reorder operations over a tankoubon's member id order (spec
 * 2026-09-21 §2/§4). Every function returns a NEW order and never touches
 * membership: the result is always a permutation of [order]. Selected ids
 * unknown to [order] are ignored; a selected block keeps its relative
 * order wherever it lands.
 */
object TankMemberOrderOps {

    /** The selected block first, then everything else in place. */
    fun moveToTop(order: List<String>, selected: Set<String>): List<String> {
        val (block, rest) = split(order, selected)
        return block + rest
    }

    /** Everything else in place, then the selected block. */
    fun moveToBottom(order: List<String>, selected: Set<String>): List<String> {
        val (block, rest) = split(order, selected)
        return rest + block
    }

    /**
     * Moves the selected block so its first member lands at 1-based
     * [position] (clamped to `1..size`), keeping the block contiguous.
     */
    fun moveTo(order: List<String>, selected: Set<String>, position: Int): List<String> {
        val (block, rest) = split(order, selected)
        if (block.isEmpty()) return order
        val index = (position - 1).coerceIn(0, rest.size)
        return rest.take(index) + block + rest.drop(index)
    }

    /**
     * Inserts the selected block right before [targetId]. A target that is
     * itself selected, or unknown, leaves the order untouched.
     */
    fun insertBefore(order: List<String>, selected: Set<String>, targetId: String): List<String> {
        if (targetId in selected) return order
        val (block, rest) = split(order, selected)
        val index = rest.indexOf(targetId)
        if (block.isEmpty() || index < 0) return order
        return rest.take(index) + block + rest.drop(index)
    }

    /** The whole order reversed. */
    fun reverse(order: List<String>): List<String> = order.asReversed().toList()

    /** Only the selected members swap places with each other; the rest stay put. */
    fun reverseSelected(order: List<String>, selected: Set<String>): List<String> {
        val picked = order.filter { it in selected }.asReversed()
        var next = 0
        return order.map { id -> if (id in selected) picked[next++] else id }
    }

    /**
     * Sorts by [TankTitleSortKey] over [titleOf]; equal keys fall back to
     * the title, then the id, so the result is deterministic.
     */
    fun sortByTitle(order: List<String>, titleOf: (String) -> String): List<String> {
        val keyed = order.map { id -> Triple(id, titleOf(id), TankTitleSortKey.parse(titleOf(id))) }
        return keyed.sortedWith { a, b ->
            val c = TankTitleSortKey.comparator.compare(a.third, b.third)
            if (c != 0) c else a.second.compareTo(b.second).takeIf { it != 0 } ?: a.first.compareTo(b.first)
        }.map { it.first }
    }

    private fun split(order: List<String>, selected: Set<String>): Pair<List<String>, List<String>> =
        order.partition { it in selected }
}
