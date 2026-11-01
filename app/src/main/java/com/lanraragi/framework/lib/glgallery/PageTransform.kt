/*
 * Additive framework type for the stamp overlay (see ReaderStampsController).
 */

package com.lanraragi.framework.lib.glgallery

import android.graphics.RectF

/**
 * Immutable snapshot of where one page's image currently sits on screen
 * (GalleryView-local coordinates, zoom/pan applied). Published on the render
 * thread by GalleryView and read on the main thread via
 * [GalleryView.getPageTransforms].
 */
class PageTransform(
    /** 0-indexed reader page. */
    @JvmField val index: Int,
    /** GalleryView-local rect of the drawn image. Never mutated after publish. */
    @JvmField val imageRect: RectF,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PageTransform) return false
        // RectF.equals directly, as Java did (no Intrinsics.areEqual step).
        return index == other.index && imageRect.equals(other.imageRect)
    }

    override fun hashCode(): Int = 31 * index + imageRect.hashCode()
}
