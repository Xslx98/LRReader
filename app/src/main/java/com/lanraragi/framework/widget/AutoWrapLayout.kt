/*
 * Copyright 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lanraragi.framework.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.core.content.withStyledAttributes
import androidx.core.view.isGone
import com.lanraragi.reader.R
import kotlin.math.max
import kotlin.math.min

/**
 * A ViewGroup that can layout views in line and auto wrap
 *
 * @author Hippo
 *
 */
class AutoWrapLayout : ViewGroup {

    private val rectList: MutableList<Rect> = ArrayList()
    private val rectPool: MutableList<Rect> = ArrayList()

    var alignment: Alignment? = null
        set(value) {
            if (value == null) {
                return
            }

            if (field != value) {
                field = value

                requestLayout()
                invalidate()
            }
        }

    // Running state of one onMeasure pass, kept in fields so that placeChild() allocates nothing
    private var rightBound = 0
    private var maxRightNoPadding = 0
    private var lastMaxBottom = 0
    private var maxBottom = 0
    private var lineStartIndex = 0

    enum class Alignment(internal val nativeInt: Int) {
        TOP(0),
        CENTER(1),
        BOTTOM(2),
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        context.withStyledAttributes(attrs, R.styleable.AutoWrapLayout, defStyle, 0) {
            val index = getInt(R.styleable.AutoWrapLayout_alignment, -1)
            if (index >= 0) {
                alignment = ALIGNMENTS_BY_ATTR_INDEX[index]
            }
        }
    }

    private fun obtainRect(): Rect {
        if (rectPool.isNotEmpty()) {
            return rectPool.removeAt(rectPool.size - 1)
        }
        return Rect()
    }

    private fun adjustBaseLine(lineHeight: Int, startIndex: Int, endIndex: Int) {
        if (alignment == Alignment.TOP) {
            return
        }

        for (index in startIndex until endIndex) {
            val child = getChildAt(index)
            val lp = child.layoutParams as MarginLayoutParams
            val rect = rectList[index]
            val offsetRaw = lineHeight - rect.height() - lp.topMargin - lp.bottomMargin
            if (alignment == Alignment.CENTER) {
                rect.offset(0, offsetRaw / 2)
            } else if (alignment == Alignment.BOTTOM) {
                rect.offset(0, offsetRaw)
            }
        }
    }

    // UPSTREAM-LEGACY: vertical mode not implemented, horizontal-only is sufficient

    /**
     * each row or line at least show one child
     *
     * horizontal only show child can show or partly show in parent
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)

        val maxWidth = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = if (heightMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)

        rightBound = paddingLeft
        maxRightNoPadding = rightBound
        lastMaxBottom = paddingTop
        maxBottom = lastMaxBottom
        lineStartIndex = 0

        // Return previous Rects to pool
        rectPool.addAll(rectList)
        rectList.clear()

        for (index in 0 until childCount) {
            if (!placeChild(index, maxWidth, maxWidth - paddingRight, maxHeight - paddingBottom)) {
                break
            }
        }

        // Handle last line baseline
        adjustBaseLine(maxBottom - lastMaxBottom, lineStartIndex, rectList.size)

        val measuredWidth = if (widthMode == MeasureSpec.EXACTLY) maxWidth else maxRightNoPadding + paddingRight
        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> maxHeight
            MeasureSpec.AT_MOST -> min(maxBottom + paddingBottom, maxHeight)
            else -> maxBottom + paddingBottom
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    /**
     * Measures child [index] and appends its rect, going to the next row when it does not fit
     * before [maxRightBound]. Returns false when that next row would begin at or below
     * [maxBottomBound], which ends the measure pass.
     */
    private fun placeChild(index: Int, maxWidth: Int, maxRightBound: Int, maxBottomBound: Int): Boolean {
        val child = getChildAt(index)
        child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        if (child.isGone) {
            return true
        }
        val lp = child.layoutParams as MarginLayoutParams

        var left = rightBound + lp.leftMargin
        rightBound = left + child.measuredWidth + lp.rightMargin
        if (rightBound > maxRightBound) { // Go to next row
            // Adjust child position base on baseline
            adjustBaseLine(maxBottom - lastMaxBottom, lineStartIndex, index)

            // If child can't show in parent begin this line
            if (maxBottom >= maxBottomBound) {
                return false
            }

            // If it is first item in line, try to show it all
            if (index == lineStartIndex) {
                val widthSpec = MeasureSpec.makeMeasureSpec(
                    maxWidth - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin,
                    MeasureSpec.AT_MOST,
                )
                child.measure(widthSpec, MeasureSpec.UNSPECIFIED)
            }
            left = paddingLeft + lp.leftMargin
            rightBound = left + child.measuredWidth + lp.rightMargin

            lastMaxBottom = maxBottom
            lineStartIndex = index
        }
        val right = left + child.measuredWidth
        val top = lastMaxBottom + lp.topMargin
        val bottom = top + child.measuredHeight
        val bottomBound = bottom + lp.bottomMargin

        // Update max
        maxRightNoPadding = max(maxRightNoPadding, rightBound)
        maxBottom = max(maxBottom, bottomBound)

        rectList.add(obtainRect().apply { set(left, top, right, bottom) })
        return true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val count = rectList.size
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.isGone) {
                continue
            }
            val rect = rectList[i]
            child.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): MarginLayoutParams = MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): MarginLayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams?): MarginLayoutParams = MarginLayoutParams(p)

    private companion object {
        private val ALIGNMENTS_BY_ATTR_INDEX = arrayOf(Alignment.TOP, Alignment.CENTER, Alignment.BOTTOM)
    }
}
