/*
 * Additive framework type for the stamp overlay (see ReaderStampsController).
 */
package com.hippo.lib.glgallery;

import android.graphics.RectF;

/**
 * Immutable snapshot of where one page's image currently sits on screen
 * (GalleryView-local coordinates, zoom/pan applied). Published on the render
 * thread by GalleryView and read on the main thread via
 * {@link GalleryView#getPageTransforms()}.
 */
public final class PageTransform {

    /** 0-indexed reader page. */
    public final int index;
    /** GalleryView-local rect of the drawn image. Never mutated after publish. */
    public final RectF imageRect;

    public PageTransform(int index, RectF imageRect) {
        this.index = index;
        this.imageRect = imageRect;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PageTransform)) return false;
        PageTransform other = (PageTransform) o;
        return index == other.index && imageRect.equals(other.imageRect);
    }

    @Override
    public int hashCode() {
        return 31 * index + imageRect.hashCode();
    }
}
