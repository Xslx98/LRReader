package com.lanraragi.framework.lib.glgallery

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * One scale and offset shared by the two pages of a spread. Both page views
 * span the whole slot, and each one's destination rect is derived from here,
 * so zoom and pan move the two pages as one image and a pan crosses the seam.
 *
 * At [scale] 1 (joint fit) each page fits its half of the slot, the [LEFT]
 * page against the seam from the left and the [RIGHT] one from the right,
 * and the pair is centred. Scroll, fling bounds and double-tap steps act on
 * the union of both rects, as [ImageView] does for one image.
 *
 * Plain float math on preallocated fields: nothing here allocates.
 */
internal class SpreadViewport : PanTarget {

    private var slotWidth = 0
    private var slotHeight = 0
    private val textureWidth = IntArray(2)
    private val textureHeight = IntArray(2)

    // Joint-fit rects per side and their union, slot coordinates at scale 1.
    private val baseLeft = FloatArray(2)
    private val baseTop = FloatArray(2)
    private val baseRight = FloatArray(2)
    private val baseBottom = FloatArray(2)
    private val fitScale = FloatArray(2)
    private var unionLeft = 0f
    private var unionTop = 0f
    private var unionRight = 0f
    private var unionBottom = 0f

    /** Scale relative to joint fit. */
    override var scale = 1f
        private set
    private var translateX = 0f
    private var translateY = 0f

    private var dirty = true

    /** Bumped on every change; a page view re-reads its rect when it moved. */
    var version = 0
        private set

    /** Forgets both pages and returns to joint fit; for a slot that is filled again. */
    fun reset() {
        textureWidth.fill(0)
        textureHeight.fill(0)
        scale = 1f
        translateX = 0f
        translateY = 0f
        dirty = true
        version++
    }

    /** The texture size of [side]'s page; 0 x 0 while it has no image. */
    fun setPage(side: Int, width: Int, height: Int) {
        if (textureWidth[side] == width && textureHeight[side] == height) {
            return
        }
        textureWidth[side] = width
        textureHeight[side] = height
        dirty = true
        version++
    }

    override val isLoaded: Boolean
        get() = textureWidth[LEFT] > 0 || textureWidth[RIGHT] > 0

    /** Lays the pair out in a slot of [width] x [height]; cheap when nothing changed. */
    fun layout(width: Int, height: Int) {
        if (!dirty && width == slotWidth && height == slotHeight) {
            return
        }
        slotWidth = width
        slotHeight = height
        dirty = false
        val half = width / 2f
        for (side in LEFT..RIGHT) {
            var w = textureWidth[side]
            var h = textureHeight[side]
            if (w <= 0 || h <= 0) {
                // No image yet: take the other page's shape so the loaded one
                // keeps its place; with neither, a portrait placeholder.
                val other = 1 - side
                w = if (textureWidth[other] > 0) textureWidth[other] else PLACEHOLDER_WIDTH
                h = if (textureHeight[other] > 0) textureHeight[other] else PLACEHOLDER_HEIGHT
            }
            val fit = min(half / w, height.toFloat() / h)
            fitScale[side] = fit
            val fitWidth = w * fit
            val fitHeight = h * fit
            baseLeft[side] = if (side == LEFT) half - fitWidth else half
            baseRight[side] = baseLeft[side] + fitWidth
            baseTop[side] = (height - fitHeight) / 2f
            baseBottom[side] = baseTop[side] + fitHeight
        }
        unionLeft = min(baseLeft[LEFT], baseLeft[RIGHT])
        unionTop = min(baseTop[LEFT], baseTop[RIGHT])
        unionRight = max(baseRight[LEFT], baseRight[RIGHT])
        unionBottom = max(baseBottom[LEFT], baseBottom[RIGHT])
        if (scale == 1f) {
            translateX = 0f
            translateY = 0f
        }
        adjust()
        version++
    }

    /** Writes [side]'s destination rect, slot coordinates, into [out]. */
    fun dst(side: Int, out: RectF) {
        out.set(
            baseLeft[side] * scale + translateX,
            baseTop[side] * scale + translateY,
            baseRight[side] * scale + translateX,
            baseBottom[side] * scale + translateY,
        )
    }

    /** The side whose page is under slot x [x]: left or right of the seam. */
    fun sideAt(x: Float): Int = if (x < baseRight[LEFT] * scale + translateX) LEFT else RIGHT

    private fun left() = unionLeft * scale + translateX

    private fun top() = unionTop * scale + translateY

    private fun right() = unionRight * scale + translateX

    private fun bottom() = unionBottom * scale + translateY

    // As ImageView.adjustPosition, over the union: centre what is smaller
    // than the slot, keep what is larger covering it.
    private fun adjust() {
        translateX += fitOffset(left(), right(), slotWidth)
        translateY += fitOffset(top(), bottom(), slotHeight)
    }

    private fun fitOffset(start: Float, end: Float, size: Int): Float = when {
        end - start <= size -> (size - (end - start)) / 2f - start
        start > 0f -> -start
        end < size -> size - end
        else -> 0f
    }

    // As ImageView.scroll, over the union.
    override fun scroll(dx: Int, dy: Int, remain: IntArray) {
        val oldX = translateX
        val oldY = translateY
        if (right() - left() > slotWidth) {
            translateX -= dx
            val fix = fitOffset(left(), right(), slotWidth)
            translateX += fix
            remain[0] = fix.toInt()
        } else {
            remain[0] = dx
        }
        if (bottom() - top() > slotHeight) {
            translateY -= dy
            val fix = fitOffset(top(), bottom(), slotHeight)
            translateY += fix
            remain[1] = fix.toInt()
        } else {
            remain[1] = dy
        }
        if (translateX != oldX || translateY != oldY) {
            version++
        }
    }

    override fun scale(focusX: Float, focusY: Float, scale: Float) {
        val newScale = (this.scale * scale).coerceIn(1f, maxScale())
        if (newScale == this.scale) {
            return
        }
        val factor = newScale / this.scale
        translateX = focusX + (translateX - focusX) * factor
        translateY = focusY + (translateY - focusY) * factor
        this.scale = newScale
        adjust()
        version++
    }

    // Each texture stays within ImageView's 10x limit.
    private fun maxScale(): Float = max(1f, TEXTURE_SCALE_MAX / max(fitScale[LEFT], fitScale[RIGHT]))

    /** Joint fit, then the wider page's width across the whole slot. */
    override fun getScaleDefault(scaleDefault: FloatArray) {
        val pageWidth = max(baseRight[LEFT] - baseLeft[LEFT], baseRight[RIGHT] - baseLeft[RIGHT])
        val oneWide = if (pageWidth > 0f) (slotWidth / pageWidth).coerceIn(1f, maxScale()) else 1f
        scaleDefault[0] = 1f
        scaleDefault[1] = oneWide
        scaleDefault[2] = oneWide
        scaleDefault[3] = oneWide
    }

    override fun canFling(): Boolean = canFlingHorizontally() || canFlingVertically()

    override fun canFlingHorizontally(): Boolean = left() < 0f || right() > slotWidth

    override fun canFlingVertically(): Boolean = top() < 0f || bottom() > slotHeight

    override fun getMaxDx(): Int = max(0, -left().toInt())

    override fun getMinDx(): Int = min(0, slotWidth - right().toInt())

    override fun getMaxDy(): Int = max(0, -top().toInt())

    override fun getMinDy(): Int = min(0, slotHeight - bottom().toInt())

    companion object {
        /** The page on the left of the screen. */
        const val LEFT = 0

        /** The page on the right of the screen. */
        const val RIGHT = 1

        private const val PLACEHOLDER_WIDTH = 7
        private const val PLACEHOLDER_HEIGHT = 10
        private const val TEXTURE_SCALE_MAX = 10f
    }
}
