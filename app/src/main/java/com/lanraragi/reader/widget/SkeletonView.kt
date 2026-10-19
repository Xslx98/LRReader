package com.lanraragi.reader.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.lanraragi.reader.R

/**
 * A loading skeleton for a network list: rows (or tiles) shaped like the
 * real items, in the placeholder tone, breathing with [PlaceholderPulse].
 * It stays blank for the first [REVEAL_DELAY_MS] it is visible, so a fast
 * load never flashes it; with animations off it is drawn static.
 */
class SkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Shape {
        /** Cover on the left, title and detail bars to its right (home list, tank list). */
        LIST,

        /** Cover tiles in columns (home grid). */
        GRID,

        /** Text-only rows with a small leading icon (category list). */
        TEXT,
    }

    var shape: Shape = Shape.LIST
        set(value) {
            field = value
            invalidate()
        }

    /** LIST: the cover block size. */
    var coverWidth = dp(LIST_COVER_WIDTH_DP)
    var coverHeight = dp(LIST_COVER_HEIGHT_DP)

    /** GRID: the target column width, as the real grid computes it. */
    var columnWidth = dp(GRID_COLUMN_WIDTH_DP)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val baseColor = PlaceholderPulse.baseColor(context)
    private val radius = dp(CORNER_DP).toFloat()
    private val gap = dp(GAP_DP)
    private var startTime = 0L
    private var revealed = false
    private val reveal = Runnable {
        revealed = true
        startTime = SystemClock.uptimeMillis()
        invalidate()
    }

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.SkeletonView)
            shape = Shape.entries[a.getInt(R.styleable.SkeletonView_skeletonShape, 0)]
            coverWidth = a.getDimensionPixelSize(R.styleable.SkeletonView_skeletonCoverWidth, coverWidth)
            coverHeight = a.getDimensionPixelSize(R.styleable.SkeletonView_skeletonCoverHeight, coverHeight)
            a.recycle()
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(reveal)
        revealed = false
        if (isVisible) postDelayed(reveal, REVEAL_DELAY_MS)
    }

    override fun onDraw(canvas: Canvas) {
        if (!revealed) return
        val animate = ValueAnimator.areAnimatorsEnabled()
        paint.color = PlaceholderPulse.color(baseColor, SystemClock.uptimeMillis() - startTime, animate)
        when (shape) {
            Shape.LIST -> drawList(canvas)
            Shape.GRID -> drawGrid(canvas)
            Shape.TEXT -> drawText(canvas)
        }
        if (animate) postInvalidateDelayed(PlaceholderPulse.FRAME_MS)
    }

    private fun drawList(canvas: Canvas) {
        val left = paddingLeft + gap
        val right = width - paddingRight - gap
        val rowHeight = coverHeight + gap * 2
        val barHeight = dp(BAR_HEIGHT_DP)
        var top = paddingTop + gap
        while (top < height) {
            block(canvas, left, top, left + coverWidth, top + coverHeight)
            val textLeft = left + coverWidth + gap * 2
            val textWidth = right - textLeft
            block(canvas, textLeft, top + gap, textLeft + textWidth * TITLE_SHARE, top + gap + barHeight)
            block(canvas, textLeft, top + gap * 3 + barHeight, textLeft + textWidth * DETAIL_SHARE,
                top + gap * 3 + barHeight * 2)
            block(canvas, textLeft, top + coverHeight - barHeight - gap, textLeft + textWidth * FOOTER_SHARE,
                top + coverHeight - gap)
            top += rowHeight
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val inner = width - paddingLeft - paddingRight
        val columns = (inner / columnWidth.coerceAtLeast(1)).coerceAtLeast(1)
        val cell = inner / columns
        val tileHeight = ((cell - gap * 2) / GRID_ASPECT).toInt()
        var top = paddingTop + gap
        while (top < height) {
            for (c in 0 until columns) {
                val left = paddingLeft + c * cell + gap
                block(canvas, left, top, left + cell - gap * 2, top + tileHeight)
            }
            top += tileHeight + gap * 2
        }
    }

    private fun drawText(canvas: Canvas) {
        val left = paddingLeft + dp(TEXT_INSET_DP)
        val right = width - paddingRight - dp(TEXT_INSET_DP)
        val rowHeight = dp(TEXT_ROW_DP)
        val icon = dp(TEXT_ICON_DP)
        val barHeight = dp(BAR_HEIGHT_DP)
        var top = paddingTop
        while (top < height) {
            val iconTop = top + (rowHeight - icon) / 2
            block(canvas, left, iconTop, left + icon, iconTop + icon)
            val textLeft = left + icon + dp(TEXT_INSET_DP)
            val barTop = top + (rowHeight - barHeight) / 2
            block(canvas, textLeft, barTop, textLeft + (right - textLeft) * TITLE_SHARE, barTop + barHeight)
            top += rowHeight
        }
    }

    private fun block(canvas: Canvas, left: Number, top: Number, right: Number, bottom: Number) {
        rect.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        /** Loads faster than this show nothing at all (decision 20). */
        const val REVEAL_DELAY_MS = 150L
        const val LIST_COVER_WIDTH_DP = 80
        const val LIST_COVER_HEIGHT_DP = 120
        const val GRID_COLUMN_WIDTH_DP = 120
        const val GRID_ASPECT = 0.67f
        const val GAP_DP = 4
        const val CORNER_DP = 2
        const val BAR_HEIGHT_DP = 12
        const val TEXT_ROW_DP = 48
        const val TEXT_ICON_DP = 24
        const val TEXT_INSET_DP = 16
        const val TITLE_SHARE = 0.7f
        const val DETAIL_SHARE = 0.45f
        const val FOOTER_SHARE = 0.35f
    }
}
