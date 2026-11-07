package com.lanraragi.framework.lib.image

import com.lanraragi.framework.lib.glgallery.GalleryView

/**
 * How the reader shows a page at 1x zoom, which decides how far a page decode
 * may be sampled (owner decision 2026-10-06, audit R1 / PERF-02). See
 * [Image.readerSampleSize].
 */
enum class PageFit {
    /** The whole page inside the screen (pager, SCALE_FIT). */
    FIT,

    /** Page width = screen width (pager SCALE_FIT_WIDTH, and every top-to-bottom page). */
    FIT_WIDTH,

    /** Page height = screen height (pager SCALE_FIT_HEIGHT). */
    FIT_HEIGHT,

    /** Decoded pixels shown 1:1 (SCALE_ORIGIN, SCALE_FIXED at its 1.0 value). */
    ORIGIN;

    companion object {
        /**
         * Effective fit for a reading direction and scale mode. The top-to-bottom
         * layout ignores the scale mode: ScrollLayoutManager measures every page
         * at the view width and lets the height follow.
         */
        @JvmStatic
        fun of(layoutMode: Int, scaleMode: Int): PageFit {
            if (layoutMode == GalleryView.LAYOUT_TOP_TO_BOTTOM) return FIT_WIDTH
            return when (scaleMode) {
                GalleryView.SCALE_FIT -> FIT
                GalleryView.SCALE_FIT_WIDTH -> FIT_WIDTH
                GalleryView.SCALE_FIT_HEIGHT -> FIT_HEIGHT
                else -> ORIGIN
            }
        }
    }
}
