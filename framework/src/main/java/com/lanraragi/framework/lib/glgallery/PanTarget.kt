package com.lanraragi.framework.lib.glgallery

/**
 * What the pager's gestures (scroll, fling, pinch, double tap, edge glow)
 * move on the current slot: the page's [ImageView] for a single page, the
 * slot's [SpreadViewport] for a two-page spread. Coordinates are the slot's,
 * which are the gallery view's while the slot is current.
 */
internal interface PanTarget {
    val isLoaded: Boolean

    /** The scale a double tap compares against [getScaleDefault]. */
    val scale: Float

    /** Moves the image by -[dx], -[dy]; writes what it could not take into [remain]. */
    fun scroll(dx: Int, dy: Int, remain: IntArray)

    /** Multiplies the scale by [scale] around ([focusX], [focusY]). */
    fun scale(focusX: Float, focusY: Float, scale: Float)

    /** The double-tap steps, sorted, into the four slots of [scaleDefault]. */
    fun getScaleDefault(scaleDefault: FloatArray)

    fun canFling(): Boolean

    fun canFlingHorizontally(): Boolean

    fun canFlingVertically(): Boolean

    fun getMaxDx(): Int

    fun getMinDx(): Int

    fun getMaxDy(): Int

    fun getMinDy(): Int
}
