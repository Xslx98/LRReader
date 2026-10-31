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

@file:Suppress("LongMethod", "LoopWithTooManyJumpStatements") // TODO(2.4-idiom)

package com.lanraragi.framework.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.lanraragi.reader.R

/**
 * A ViewGroup that can layout views in line and auto wrap
 *
 * @author Hippo
 *
 */
class AutoWrapLayout : ViewGroup {

    private val rectList: MutableList<Rect> = ArrayList()
    private val mRectPool: MutableList<Rect> = ArrayList()

    private var mAlignment: Alignment? = null

    enum class Alignment(internal val nativeInt: Int) {
        TOP(0),
        CENTER(1),
        BOTTOM(2),
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.AutoWrapLayout, defStyle, 0)

        val index = a.getInt(R.styleable.AutoWrapLayout_alignment, -1)
        if (index >= 0) {
            setAlignment(sBaseLineArray[index])
        }

        a.recycle()
    }

    fun setAlignment(baseLine: Alignment?) {
        if (baseLine == null) {
            return
        }

        if (mAlignment != baseLine) {
            mAlignment = baseLine

            requestLayout()
            invalidate()
        }
    }

    fun getAlignment(): Alignment? = mAlignment

    private fun obtainRect(): Rect {
        if (mRectPool.isNotEmpty()) {
            return mRectPool.removeAt(mRectPool.size - 1)
        }
        return Rect()
    }

    private fun adjustBaseLine(lineHeight: Int, startIndex: Int, endIndex: Int) {
        if (mAlignment == Alignment.TOP) {
            return
        }

        for (index in startIndex until endIndex) {
            val child = getChildAt(index)
            val lp = child.layoutParams as MarginLayoutParams
            val rect = rectList[index]
            val offsetRaw = lineHeight - rect.height() - lp.topMargin - lp.bottomMargin
            if (mAlignment == Alignment.CENTER) {
                rect.offset(0, offsetRaw / 2)
            } else if (mAlignment == Alignment.BOTTOM) {
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

        var maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        var maxHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (widthMode == MeasureSpec.UNSPECIFIED) {
            maxWidth = Int.MAX_VALUE
        }
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            maxHeight = Int.MAX_VALUE
        }

        val paddingLeft = paddingLeft
        val paddingTop = paddingTop
        val paddingRight = paddingRight
        val paddingBottom = paddingBottom

        val maxRightBound = maxWidth - paddingRight
        val maxBottomBound = maxHeight - paddingBottom

        var left: Int
        var top: Int
        var right: Int
        var bottom: Int
        var rightBound = paddingLeft
        var maxRightNoPadding = rightBound
        var bottomBound: Int
        var lastMaxBottom = paddingTop
        var maxBottom = lastMaxBottom
        var childWidth: Int
        var childHeight: Int

        var lineStartIndex = 0
        var lineEndIndex: Int // endIndex + 1

        // Return previous Rects to pool
        mRectPool.addAll(rectList)
        rectList.clear()

        val childCount = childCount
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            if (child.visibility == View.GONE) {
                continue
            }
            val lp = child.layoutParams as MarginLayoutParams
            childWidth = child.measuredWidth
            childHeight = child.measuredHeight

            left = rightBound + lp.leftMargin
            right = left + childWidth
            rightBound = right + lp.rightMargin
            if (rightBound > maxRightBound) { // Go to next row
                lineEndIndex = index
                // Adjust child position base on baseline
                adjustBaseLine(maxBottom - lastMaxBottom, lineStartIndex, lineEndIndex)

                // If child can't show in parent begin this line
                if (maxBottom >= maxBottomBound) {
                    break
                }

                // If it is first item in line, try to show it all
                if (lineEndIndex == lineStartIndex) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(
                            maxWidth - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin,
                            MeasureSpec.AT_MOST,
                        ),
                        MeasureSpec.UNSPECIFIED,
                    )
                    childWidth = child.measuredWidth
                    childHeight = child.measuredHeight
                }
                left = paddingLeft + lp.leftMargin
                right = left + childWidth
                rightBound = right + lp.rightMargin

                lastMaxBottom = maxBottom
                top = lastMaxBottom + lp.topMargin
                bottom = top + childHeight
                bottomBound = bottom + lp.bottomMargin

                lineStartIndex = index
            } else {
                top = lastMaxBottom + lp.topMargin
                bottom = top + childHeight
                bottomBound = bottom + lp.bottomMargin
            }
            // Update max
            if (rightBound > maxRightNoPadding) {
                maxRightNoPadding = rightBound
            }
            if (bottomBound > maxBottom) {
                maxBottom = bottomBound
            }
            val rect = obtainRect()
            rect.left = left
            rect.top = top
            rect.right = right
            rect.bottom = bottom
            rectList.add(rect)
        }

        // Handle last line baseline
        adjustBaseLine(maxBottom - lastMaxBottom, lineStartIndex, rectList.size)

        val measuredWidth: Int
        var measuredHeight: Int

        if (widthMode == MeasureSpec.EXACTLY) {
            measuredWidth = maxWidth
        } else {
            measuredWidth = maxRightNoPadding + paddingRight
        }
        if (heightMode == MeasureSpec.EXACTLY) {
            measuredHeight = maxHeight
        } else {
            measuredHeight = maxBottom + paddingBottom
            if (heightMode == MeasureSpec.AT_MOST) {
                measuredHeight = if (measuredHeight > maxHeight) maxHeight else measuredHeight
            }
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val count = rectList.size
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) {
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
        private val sBaseLineArray = arrayOf(Alignment.TOP, Alignment.CENTER, Alignment.BOTTOM)
    }
}
