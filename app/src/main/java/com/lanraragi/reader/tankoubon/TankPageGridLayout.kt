package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.gallery.TankPageMath

/**
 * Pure layout of the tank detail page's continuous page grid (spec
 * 2026-09-22 §4.7): one full-span [Item.Divider] per member followed by
 * that member's pages as [Item.Page] cells addressed by GLOBAL 0-indexed
 * page, so a tap lands on the same global page the composite reader uses.
 * Zero-page members still get their divider (the title is information).
 */
object TankPageGridLayout {

    sealed class Item {
        /** Full-span header row for member [memberIndex]. */
        data class Divider(val memberIndex: Int) : Item()

        /** One page cell: global page [global0] = member [memberIndex]'s local page [page0]. */
        data class Page(val global0: Int, val memberIndex: Int, val page0: Int) : Item()
    }

    class Layout(val items: List<Item>) {
        private val positionByGlobal: IntArray

        /** Total pages across members (= the last global page + 1). */
        val totalPages: Int

        init {
            var pages = 0
            for (item in items) if (item is Item.Page) pages++
            totalPages = pages
            positionByGlobal = IntArray(pages)
            items.forEachIndexed { position, item ->
                if (item is Item.Page) positionByGlobal[item.global0] = position
            }
        }

        val size: Int get() = items.size

        fun itemAt(position: Int): Item = items[position]

        /** Global page of the cell at [position], or null for a divider / out of range. */
        fun globalAt(position: Int): Int? = (items.getOrNull(position) as? Item.Page)?.global0

        /** Adapter position of global page [global0], or -1 when out of range. */
        fun positionOfGlobal(global0: Int): Int =
            if (global0 in 0 until totalPages) positionByGlobal[global0] else -1

        /** Span of the cell at [position] in a grid of [spanCount] columns. */
        fun spanSize(position: Int, spanCount: Int): Int =
            if (items.getOrNull(position) is Item.Divider) spanCount else 1
    }

    fun build(pagecounts: List<Int>): Layout {
        val counts = pagecounts.map { it.coerceAtLeast(0) }
        val offsets = TankPageMath.pageOffsets(counts)
        val items = ArrayList<Item>(counts.size + offsets.last())
        counts.forEachIndexed { memberIndex, count ->
            items.add(Item.Divider(memberIndex))
            val start = offsets[memberIndex]
            for (page0 in 0 until count) {
                items.add(Item.Page(global0 = start + page0, memberIndex = memberIndex, page0 = page0))
            }
        }
        return Layout(items)
    }
}
