package com.lanraragi.framework.lib.glview.image

import android.graphics.RectF

/**
 * Which part of a page's texture is worth holding on the GPU (audit 2026-10-13
 * PERF-02). Tiles within [UPLOAD_MARGIN_SCREENS] screens of the viewport are
 * uploaded ahead of time, nearest first; tiles beyond [KEEP_MARGIN_SCREENS]
 * screens give their GL texture back and are uploaded again from the decoded
 * image when they come near. A long strip then holds about five screens of
 * tiles instead of all of them; the gap between the two margins keeps a small
 * back-and-forth scroll from releasing and re-uploading the same tiles.
 */
object TileRegions {

    const val UPLOAD_MARGIN_SCREENS = 1f
    const val KEEP_MARGIN_SCREENS = 2f

    /**
     * Writes into [out] the root rect ([rootW] x [rootH], the screen) in the
     * texture coordinates of a [texW] x [texH] texture drawn at [dst]
     * (view-local), for a view at ([viewX], [viewY]) in the root. Not clipped
     * to the texture; empty when the mapping is undefined.
     */
    @JvmStatic
    @Suppress("LongParameterList")
    fun screenInTexture(
        out: RectF,
        dst: RectF,
        viewX: Int,
        viewY: Int,
        rootW: Int,
        rootH: Int,
        texW: Int,
        texH: Int,
    ) {
        if (dst.isEmpty || minOf(rootW, rootH, texW, texH) <= 0) {
            out.setEmpty()
            return
        }
        val sx = texW / dst.width()
        val sy = texH / dst.height()
        out.set(
            (-viewX - dst.left) * sx,
            (-viewY - dst.top) * sy,
            (-viewX + rootW - dst.left) * sx,
            (-viewY + rootH - dst.top) * sy,
        )
    }

    /** [screen] grown by [margin] of its own size on every side, into [out]. */
    @JvmStatic
    fun band(out: RectF, screen: RectF, margin: Float) {
        if (screen.isEmpty) {
            out.setEmpty()
            return
        }
        val dx = screen.width() * margin
        val dy = screen.height() * margin
        out.set(screen.left - dx, screen.top - dy, screen.right + dx, screen.bottom + dy)
    }

    /** Distance between the rect ([left], [top], [right], [bottom]) and [screen]; 0 when they overlap. */
    @JvmStatic
    fun gap(screen: RectF, left: Float, top: Float, right: Float, bottom: Float): Float {
        val dx = maxOf(0f, screen.left - right, left - screen.right)
        val dy = maxOf(0f, screen.top - bottom, top - screen.bottom)
        return dx + dy
    }
}
