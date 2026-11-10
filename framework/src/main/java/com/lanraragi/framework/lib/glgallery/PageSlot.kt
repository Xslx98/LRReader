package com.lanraragi.framework.lib.glgallery

/**
 * One position of the pager: the previous, the current or the next turn.
 * [first] is the page shown there, the first in reading order. [partner] is
 * the second page of a two-page spread; single-page reading never fills it.
 * The two pages of a spread share [viewport].
 *
 * A page turn rotates the three slots and refills the one that wraps
 * around, so the slots themselves are allocated once per pager.
 */
internal class PageSlot {
    @JvmField
    var first: GalleryPageView? = null

    @JvmField
    var partner: GalleryPageView? = null

    @JvmField
    val viewport = SpreadViewport()

    /** The last page in reading order, [GalleryPageView.INVALID_INDEX] when empty. */
    fun lastIndex(): Int = (partner ?: first)?.index ?: GalleryPageView.INVALID_INDEX

    /** Whether page [index] is shown here. */
    operator fun contains(index: Int): Boolean {
        val first = this.first ?: return false
        return index >= first.index && index <= lastIndex()
    }

    /** Whether this slot shows exactly [spread]. */
    fun holds(spread: Spread): Boolean {
        val first = this.first ?: return false
        return first.index == spread.first && lastIndex() == spread.last
    }
}
