package com.lanraragi.framework.lib.glgallery

/**
 * Pairs pages into two-page spreads. Pure and allocation-free: no GL, no
 * Android, results are [Spread] values (one packed `Long`).
 *
 * The planner works in reading order only. Which page of a pair goes on the
 * right (right-to-left books) or on the left is the slot layout's business, so
 * nothing here depends on the reading direction.
 *
 * Rules:
 * - A spread is two consecutive pages, or one page alone when it is wide, the
 *   next page is wide, the next page is a pair break, or it is the last page.
 * - Page 0 and every [Pages.isPairBreak] page restart pairing. A break page
 *   also stands alone when "first page alone" is on, and [shifted] flips that.
 * - A page of unknown aspect counts as portrait.
 * - Natural pairing ([spreadContaining]) walks forward from the nearest break.
 *   [spreadFrom], [spreadAfter] and [spreadBefore] instead build around an
 *   anchor, the spread the reader sees now: a page found to be wide re-plans
 *   only from the anchor onward and never moves what lies behind it.
 *
 * The configuration is plain fields: the layout sets them on its own thread
 * before planning. Per-archive values (the shift, the landing parity) are
 * stored by the app and handed in; the planner keeps no state between calls.
 */
internal class SpreadPlanner {

    /** What the planner reads about the pages. Implementations must not allocate. */
    interface Pages {
        /** Page count; 0 or less when empty or not known yet. */
        fun pageCount(): Int

        /** Width / height of page [index], or [UNKNOWN_ASPECT] when not known yet. */
        fun pageAspect(index: Int): Float

        /** True when pairing restarts at [index] (a tank member's first page). Page 0 always restarts it. */
        fun isPairBreak(index: Int): Boolean
    }

    /** The "first page alone" setting: a break page (the cover) stands alone. */
    @JvmField
    var firstPageAlone = true

    /** The per-archive "shift pairing by one" toggle: flips [firstPageAlone] at every break. */
    @JvmField
    var shifted = false

    /** A page whose aspect is above this is wide and takes a whole spread. */
    @JvmField
    var wideThreshold = DEFAULT_WIDE_THRESHOLD

    private val breakAlone: Boolean
        get() = firstPageAlone != shifted

    fun isWide(pages: Pages, index: Int): Boolean = pages.pageAspect(index) > wideThreshold

    private fun isBreak(pages: Pages, index: Int): Boolean = index == 0 || pages.isPairBreak(index)

    /** The spread starting at [first], with [first] as its anchor; [Spread.NONE] out of range. */
    fun spreadFrom(pages: Pages, first: Int): Spread {
        val count = pages.pageCount()
        if (first < 0 || first >= count) return Spread.NONE
        val second = first + 1
        val alone = second >= count ||
            (breakAlone && isBreak(pages, first)) ||
            isBreak(pages, second) ||
            isWide(pages, first) ||
            isWide(pages, second)
        return Spread.of(first, if (alone) first else second)
    }

    /** The spread after the one ending at [last]; [Spread.NONE] past the end. */
    fun spreadAfter(pages: Pages, last: Int): Spread = spreadFrom(pages, last + 1)

    /**
     * The spread just before the one starting at [anchor]: natural pairing,
     * but a pair that would reach into [anchor] is cut to its first page.
     * [Spread.NONE] when [anchor] is the first page.
     */
    fun spreadBefore(pages: Pages, anchor: Int): Spread {
        if (anchor <= 0) return Spread.NONE
        val natural = spreadContaining(pages, anchor - 1)
        return if (natural.last >= anchor) Spread.of(natural.first, anchor - 1) else natural
    }

    /** The natural spread containing [index]: pairing walked forward from the nearest break. */
    fun spreadContaining(pages: Pages, index: Int): Spread {
        if (index < 0 || index >= pages.pageCount()) return Spread.NONE
        var spread = spreadFrom(pages, nearestBreak(pages, index))
        while (spread.last < index) {
            spread = spreadAfter(pages, spread.last)
        }
        return spread
    }

    /**
     * Where the reader lands on [index] without a turn: open, resume, a jump,
     * rotation, a data change or switching spreads on.
     *
     * 1. With a stored [parity] (see [parityOf]), the pairing the reader saw
     *    last time: [index] starts its spread when its parity matches,
     *    otherwise it pairs with the page before it when it can.
     * 2. Without one, the natural spread when every page from the nearest break
     *    up to [index] has a known aspect, which is what reading forward gave.
     * 3. Otherwise [index] anchors its own spread: an unknown page behind it
     *    could be wide and flip the pairing, and moving the page the reader
     *    asked for is worse than an odd pair behind it.
     *
     * A stored parity wins over [shifted], so whoever changes the shift must
     * also store the parity of the spread it lands on (or drop the stored one).
     */
    fun spreadForLanding(pages: Pages, index: Int, parity: Int = PARITY_UNKNOWN): Spread {
        if (index < 0 || index >= pages.pageCount()) return Spread.NONE
        val start = nearestBreak(pages, index)
        if (parity == 0 || parity == 1) {
            if (((index - start) and 1) == parity || index == start) return spreadFrom(pages, index)
            val withPrevious = spreadFrom(pages, index - 1)
            return if (withPrevious.last == index) withPrevious else spreadFrom(pages, index)
        }
        for (p in start..index) {
            if (pages.pageAspect(p) <= UNKNOWN_ASPECT) return spreadFrom(pages, index)
        }
        return spreadContaining(pages, index)
    }

    /**
     * The pairing parity of a spread starting at [first]: its distance from
     * the nearest break, mod 2. The app stores it per archive so that
     * [spreadForLanding] repeats the pairs after the page aspects are gone
     * (a cache clear). [PARITY_UNKNOWN] for a page out of range.
     */
    fun parityOf(pages: Pages, first: Int): Int {
        if (first < 0 || first >= pages.pageCount()) return PARITY_UNKNOWN
        return (first - nearestBreak(pages, first)) and 1
    }

    private fun nearestBreak(pages: Pages, index: Int): Int {
        var p = index
        while (p > 0 && !pages.isPairBreak(p)) {
            p--
        }
        return p
    }

    companion object {
        /** [Pages.pageAspect] for a page whose size is not known yet. */
        const val UNKNOWN_ASPECT = 0f

        /** [parityOf] / [spreadForLanding] value when no pairing is stored. */
        const val PARITY_UNKNOWN = -1

        /**
         * Single pages are about 0.65-0.75 wide, two-page scans 1.3-1.5; any
         * threshold in 0.9-1.2 sorts real books the same (item 20 prototype).
         * Exactly 1.0 keeps square art paired.
         */
        const val DEFAULT_WIDE_THRESHOLD = 1.0f
    }
}

/**
 * The pages of one spread in reading order, [first] to [last] (equal for a
 * page shown alone). Packed into one `Long`, so returning it never allocates.
 */
@JvmInline
internal value class Spread private constructor(private val packed: Long) {

    val first: Int
        get() = (packed shr 32).toInt()

    val last: Int
        get() = packed.toInt()

    val isNone: Boolean
        get() = first < 0

    val isPair: Boolean
        get() = last > first

    operator fun contains(index: Int): Boolean = !isNone && index in first..last

    override fun toString(): String = when {
        isNone -> "none"
        isPair -> "$first-$last"
        else -> "$first"
    }

    companion object {
        /** No spread: out of range, or nothing before the first page. */
        val NONE = Spread(-1L)

        fun of(first: Int, last: Int): Spread = Spread((first.toLong() shl 32) or (last.toLong() and 0xFFFFFFFFL))
    }
}
