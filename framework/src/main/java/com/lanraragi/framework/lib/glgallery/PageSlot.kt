package com.lanraragi.framework.lib.glgallery

/**
 * One position of the pager: the previous, the current or the next turn.
 * [first] is the page shown there. [partner] is the second page of a
 * two-page spread; single-page reading never fills it.
 *
 * A page turn rotates the three slots and refills the one that wraps
 * around, so the slots themselves are allocated once per pager.
 */
internal class PageSlot {
    @JvmField
    var first: GalleryPageView? = null

    @JvmField
    var partner: GalleryPageView? = null
}
