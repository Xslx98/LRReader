package com.lanraragi.framework.lib.glgallery

import com.lanraragi.framework.util.minAllocatedBytes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pairing rules of [SpreadPlanner]. The planner works in reading order only,
 * so every table here holds for right-to-left and left-to-right books alike;
 * which page sits on the right is the slot layout's job (and its tests').
 */
class SpreadPlannerTest {

    /** Pages for a table row: [wide] pages are 1.4, [known] overrides, anything else is unknown. */
    private class FakePages(
        var count: Int,
        private val wide: Set<Int> = emptySet(),
        var breaks: Set<Int> = emptySet(),
    ) : SpreadPlanner.Pages {
        val known = HashMap<Int, Float>()

        /** After a cache clear even the wide pages are unknown. */
        private var blind = false
        override fun pageCount(): Int = count
        override fun pageAspect(index: Int): Float = known[index] ?: if (!blind && index in wide) WIDE else UNKNOWN
        override fun isPairBreak(index: Int): Boolean = index in breaks

        /** Every page's aspect known, as after reading the whole book. */
        fun learnAll(): FakePages {
            for (p in 0 until count) known[p] = if (p in wide) WIDE else PORTRAIT
            blind = false
            return this
        }

        fun forgetAll() {
            known.clear()
            blind = true
        }

        /** True when a page of [first]..[last] or the page after it is wide: pairing there needs the aspects. */
        fun nearWide(first: Int, last: Int): Boolean = (first..last + 1).any { it in wide }
    }

    private val planner = SpreadPlanner()

    private fun SpreadPlanner.Pages.forward(): List<Spread> {
        val spreads = ArrayList<Spread>()
        var s = planner.spreadContaining(this, 0)
        while (!s.isNone) {
            spreads += s
            s = planner.spreadAfter(this, s.last)
        }
        return spreads
    }

    private fun SpreadPlanner.Pages.backward(): List<Spread> {
        val spreads = ArrayList<Spread>()
        var s = planner.spreadContaining(this, pageCount() - 1)
        while (!s.isNone) {
            spreads += s
            s = planner.spreadBefore(this, s.first)
        }
        return spreads.asReversed()
    }

    private fun List<Spread>.text(): String = joinToString(" ")

    private data class Row(
        val name: String,
        val pages: FakePages,
        val firstAlone: Boolean,
        val shifted: Boolean,
        val expected: String,
    )

    private val rows = listOf(
        Row("even count, first alone", FakePages(6), true, false, "0 1-2 3-4 5"),
        Row("odd count, first alone", FakePages(5), true, false, "0 1-2 3-4"),
        Row("even count, first paired", FakePages(6), false, false, "0-1 2-3 4-5"),
        Row("odd count, first paired", FakePages(5), false, false, "0-1 2-3 4"),
        Row("shift flips first alone", FakePages(5), true, true, "0-1 2-3 4"),
        Row("shift with first paired", FakePages(5), false, true, "0 1-2 3-4"),
        Row("single page book", FakePages(1), true, false, "0"),
        Row("two page book, first paired", FakePages(2), false, false, "0-1"),
        Row("wide first page", FakePages(5, wide = setOf(0)), false, false, "0 1-2 3-4"),
        Row("wide second page", FakePages(5, wide = setOf(1)), false, false, "0 1 2-3 4"),
        Row("wide last page", FakePages(5, wide = setOf(4)), true, false, "0 1-2 3 4"),
        Row("wide in the middle keeps later pairs", FakePages(8, wide = setOf(3)), true, false, "0 1-2 3 4-5 6-7"),
        Row("wide at an even page shifts later pairs", FakePages(8, wide = setOf(2)), true, false, "0 1 2 3-4 5-6 7"),
        Row("two wide in a row", FakePages(6, wide = setOf(2, 3)), true, false, "0 1 2 3 4-5"),
        Row("tank break, covers alone", FakePages(9, breaks = setOf(4)), true, false, "0 1-2 3 4 5-6 7-8"),
        Row("tank break, covers paired", FakePages(9, breaks = setOf(4)), false, false, "0-1 2-3 4-5 6-7 8"),
        Row("tank break, shifted", FakePages(9, breaks = setOf(4)), true, true, "0-1 2-3 4-5 6-7 8"),
        Row("one-page member", FakePages(6, breaks = setOf(3, 4)), true, false, "0 1-2 3 4 5"),
        Row("wide member cover", FakePages(7, wide = setOf(3), breaks = setOf(3)), false, false, "0-1 2 3 4-5 6"),
    )

    private fun configure(row: Row) {
        planner.firstPageAlone = row.firstAlone
        planner.shifted = row.shifted
    }

    @Test
    fun table_forwardWalks() {
        for (row in rows) {
            configure(row)
            assertEquals(row.name, row.expected, row.pages.forward().text())
        }
    }

    @Test
    fun table_backwardWalksMatchForward() {
        // Reading from the end with every aspect known gives the same pairs.
        for (row in rows) {
            configure(row)
            row.pages.learnAll()
            assertEquals(row.name, row.expected, row.pages.backward().text())
        }
    }

    @Test
    fun everyEntryPointLandsOnTheForwardSpread() {
        // Rotation, resume and jumps land through spreadContaining, or through
        // spreadForLanding once the pages are known.
        val pages = FakePages(30, wide = setOf(7, 8, 20), breaks = setOf(12)).learnAll()
        val byPage = HashMap<Int, Spread>()
        for (s in pages.forward()) for (p in s.first..s.last) byPage[p] = s
        for (p in 0 until 30) {
            val expected = byPage.getValue(p)
            assertEquals("page $p", expected, planner.spreadContaining(pages, p))
            assertEquals("landing on page $p", expected, planner.spreadForLanding(pages, p))
            assertEquals("anchor of page $p", expected, planner.spreadFrom(pages, expected.first))
        }
    }

    @Test
    fun landing_isNaturalOnlyWhenEverythingBehindIsKnown() {
        // Page 20 after a single-page turn: the natural pair 19-20 once pages
        // 0..20 are known; until then 20 anchors its own spread.
        val pages = FakePages(30)
        assertEquals("20-21", planner.spreadForLanding(pages, 20).toString())
        for (p in 0..20) pages.known[p] = PORTRAIT
        assertEquals("19-20", planner.spreadForLanding(pages, 20).toString())
        // Only the pages from the nearest break on matter.
        val tank = FakePages(30, breaks = setOf(15))
        for (p in 15..19) tank.known[p] = PORTRAIT
        assertEquals("18-19", planner.spreadForLanding(tank, 19).toString())
    }

    @Test
    fun storedParity_repeatsThePairsAfterTheAspectsAreGone() {
        // Owner decision P2: read with every aspect known, save the parity of
        // the spread on screen, clear the cache, resume on either page of it.
        // A spread at a wide page cannot be rebuilt blind; the decode of the
        // landing pages re-plans it.
        for (row in rows) {
            configure(row)
            val pages = row.pages.learnAll()
            for (seen in pages.forward()) {
                if (pages.nearWide(seen.first, seen.last)) continue
                val parity = planner.parityOf(pages, seen.first)
                pages.forgetAll()
                for (p in seen.first..seen.last) {
                    assertEquals("${row.name}, resume on $p of $seen", seen, planner.spreadForLanding(pages, p, parity))
                }
                pages.learnAll()
            }
        }
    }

    @Test
    fun storedParity_winsOverTheNaturalPairing() {
        // The reader saw 20-21 (landed with page 19 unknown), so 20 has parity 0
        // from the break at 0. Resuming there keeps 20-21 though 19-20 is natural.
        val pages = FakePages(30).learnAll()
        assertEquals(0, planner.parityOf(pages, 20))
        assertEquals("20-21", planner.spreadForLanding(pages, 20, parity = 0).toString())
        assertEquals("20-21", planner.spreadForLanding(pages, 21, parity = 0).toString())
        assertEquals("19-20", planner.spreadForLanding(pages, 20).toString())
    }

    @Test
    fun storedParity_neverPairsAcrossABreakOrAWidePage() {
        planner.firstPageAlone = false
        val pages = FakePages(12, wide = setOf(5), breaks = setOf(8))
        // Parity 1 asks 8 to pair with 7, but 8 starts a member.
        assertEquals("8-9", planner.spreadForLanding(pages, 8, parity = 1).toString())
        // Parity 1 asks 6 to pair with 5, which is wide.
        assertEquals("6-7", planner.spreadForLanding(pages, 6, parity = 1).toString())
        assertEquals(SpreadPlanner.PARITY_UNKNOWN, planner.parityOf(pages, 12))
    }

    @Test
    fun spreadBefore_neverReachesIntoTheAnchor() {
        val pages = FakePages(10)
        // Natural pairs are 1-2 3-4; a reader anchored at 4 sees 3 alone behind it.
        assertEquals("3", planner.spreadBefore(pages, 4).toString())
        assertEquals("1-2", planner.spreadBefore(pages, 3).toString())
        assertEquals(Spread.NONE, planner.spreadBefore(pages, 0))
    }

    @Test
    fun wideFoundAhead_replansOnlyFromTheAnchor() {
        // The reader is on 3-4 when page 6 turns out wide: the next spread
        // changes, the anchor and everything behind it do not.
        val pages = FakePages(10)
        assertEquals("3-4", planner.spreadFrom(pages, 3).toString())
        pages.known[6] = WIDE
        assertEquals("3-4", planner.spreadFrom(pages, 3).toString())
        assertEquals("5", planner.spreadAfter(pages, 4).toString())
        assertEquals("1-2", planner.spreadBefore(pages, 3).toString())
    }

    @Test
    fun wideFoundInTheCurrentSpread_movesThePartnerToTheNextSpread() {
        val pages = FakePages(10)
        pages.known[4] = WIDE
        assertEquals("3", planner.spreadFrom(pages, 3).toString())
        assertEquals("4", planner.spreadAfter(pages, 3).toString())
        assertEquals("5-6", planner.spreadAfter(pages, 4).toString())
    }

    @Test
    fun unknownCountsAsPortrait_andTheThresholdIsExclusive() {
        planner.firstPageAlone = false
        val pages = FakePages(3)
        assertEquals("unknown pairs", "0-1", planner.spreadFrom(pages, 0).toString())
        pages.known[1] = 1.0f
        assertEquals("square art stays paired", "0-1", planner.spreadFrom(pages, 0).toString())
        pages.known[1] = 1.01f
        assertEquals("0", planner.spreadFrom(pages, 0).toString())
    }

    @Test
    fun remap_readsCountAndBreaksLive() {
        // TankPageMap.correct can shrink a member or move the next member's
        // first page; the planner reads both on every call.
        val pages = FakePages(10)
        assertEquals("7-8", planner.spreadFrom(pages, 7).toString())
        pages.count = 8
        assertEquals("7", planner.spreadFrom(pages, 7).toString())
        assertEquals(Spread.NONE, planner.spreadFrom(pages, 8))
        assertEquals(Spread.NONE, planner.spreadForLanding(pages, 8))
        pages.breaks = setOf(6)
        assertEquals("5", planner.spreadContaining(pages, 5).toString())
        assertEquals("6", planner.spreadContaining(pages, 6).toString())
        assertEquals(0, planner.parityOf(pages, 6))
    }

    @Test
    fun planningDoesNotAllocate() {
        // The pager plans on the render thread at every turn.
        val pages = FixedPages(400)
        fun plan() {
            var p = 0
            while (p < 400) {
                planner.spreadForLanding(pages, p, parity = p and 1)
                planner.spreadForLanding(pages, p)
                planner.spreadBefore(pages, planner.spreadAfter(pages, planner.spreadContaining(pages, p).last).first)
                p += 7
            }
        }
        plan()
        assertEquals(0L, minAllocatedBytes(5) { plan() })
    }

    /** Allocation-free pages: every 9th page wide, a break every 50. */
    private class FixedPages(private val count: Int) : SpreadPlanner.Pages {
        override fun pageCount(): Int = count
        override fun pageAspect(index: Int): Float = if (index % 9 == 4) WIDE else PORTRAIT
        override fun isPairBreak(index: Int): Boolean = index % 50 == 0
    }

    private companion object {
        const val WIDE = 1.4f
        const val PORTRAIT = 0.7f
        const val UNKNOWN = SpreadPlanner.UNKNOWN_ASPECT
    }
}
