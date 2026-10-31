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

@file:Suppress("NestedBlockDepth") // TODO(2.4-idiom)

package com.lanraragi.framework.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import com.google.android.material.imageview.ShapeableImageView
import com.lanraragi.reader.R
import com.lanraragi.reader.util.MathUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

open class FixedAspectImageView : ShapeableImageView {

    /**
     * width / height.
     *
     * Enable aspect will set AdjustViewBounds true.
     * Any negative float to disable it,
     * disable Aspect will not disable AdjustViewBounds.
     */
    var aspect = -1f
        set(ratio) {
            val aspect = if (ratio > 0) ratio else -1f
            if (aspect == field) {
                // Grid binds call this with a constant value on every bind;
                // skip the redundant requestLayout when nothing changed.
                return
            }
            field = aspect
            requestLayout()
        }

    constructor(context: Context) : super(context) {
        init(context, null, 0)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        init(context, attrs, defStyle)
    }

    @SuppressLint("ResourceType") // indices into the sorted local attr arrays, not R.styleable ids
    private fun init(context: Context, attrs: AttributeSet?, defStyle: Int) {
        var a: TypedArray

        // Make sure we get value from xml
        a = context.obtainStyledAttributes(attrs, MIN_ATTRS, defStyle, 0)
        minimumWidth = a.getDimensionPixelSize(0, 0)
        minimumHeight = a.getDimensionPixelSize(1, 0)
        a.recycle()

        a = context.obtainStyledAttributes(attrs, ATTRS, defStyle, 0)
        adjustViewBounds = a.getBoolean(0, false)
        maxWidth = a.getDimensionPixelSize(1, Int.MAX_VALUE)
        maxHeight = a.getDimensionPixelSize(2, Int.MAX_VALUE)
        a.recycle()

        a = context.obtainStyledAttributes(attrs, R.styleable.FixedAspectImageView, defStyle, 0)
        aspect = a.getFloat(R.styleable.FixedAspectImageView_aspect, -1f)
        a.recycle()
    }

    private fun resolveAdjustedSize(desiredSize: Int, minSize: Int, maxSize: Int, measureSpec: Int): Int {
        var result = desiredSize
        val specMode = MeasureSpec.getMode(measureSpec)
        val specSize = MeasureSpec.getSize(measureSpec)
        when (specMode) {
            // Parent says we can be as big as we want. Just don't be smaller
            // than min size, and don't be larger than max size.
            MeasureSpec.UNSPECIFIED -> result = MathUtils.clamp(desiredSize, minSize, maxSize)
            // Parent says we can be as big as we want, up to specSize.
            // Don't be larger than specSize, and don't be smaller
            // than min size, and don't be larger than max size.
            MeasureSpec.AT_MOST -> result = min(MathUtils.clamp(desiredSize, minSize, maxSize), specSize)
            // No choice. Do what we are told.
            MeasureSpec.EXACTLY -> result = specSize
        }
        return result
    }

    private fun isSizeAcceptable(size: Int, minSize: Int, maxSize: Int, measureSpec: Int): Boolean {
        val specMode = MeasureSpec.getMode(measureSpec)
        val specSize = MeasureSpec.getSize(measureSpec)
        return when (specMode) {
            // Parent says we can be as big as we want. Just don't be smaller
            // than min size, and don't be larger than max size.
            MeasureSpec.UNSPECIFIED -> size >= minSize && size <= maxSize
            // Parent says we can be as big as we want, up to specSize.
            // Don't be larger than specSize, and don't be smaller
            // than min size, and don't be larger than max size.
            MeasureSpec.AT_MOST -> size <= specSize && size >= minSize && size <= maxSize
            // No choice.
            MeasureSpec.EXACTLY -> size == specSize
            // WTF? Return true to make you happy. (´・ω・`)
            else -> true
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w: Int
        var h: Int

        // Desired aspect ratio of the view's contents (not including padding)
        var desiredAspect = 0.0f

        // We are allowed to change the view's width
        var resizeWidth = false

        // We are allowed to change the view's height
        var resizeHeight = false

        val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)

        val drawable = drawable
        if (drawable == null) {
            // If no drawable, its intrinsic size is 0.
            w = 0
            h = 0

            // Aspect is forced set.
            if (aspect > 0.0f) {
                resizeWidth = widthSpecMode != MeasureSpec.EXACTLY
                resizeHeight = heightSpecMode != MeasureSpec.EXACTLY
                desiredAspect = aspect
            }
        } else {
            w = drawable.intrinsicWidth
            h = drawable.intrinsicHeight
            if (w <= 0) w = 1
            if (h <= 0) h = 1

            if (adjustViewBounds) {
                // We are supposed to adjust view bounds to match the aspect
                // ratio of our drawable. See if that is possible.
                resizeWidth = widthSpecMode != MeasureSpec.EXACTLY
                resizeHeight = heightSpecMode != MeasureSpec.EXACTLY
                desiredAspect = w.toFloat() / h.toFloat()
            } else if (aspect > 0.0f) {
                // Aspect is forced set.
                resizeWidth = widthSpecMode != MeasureSpec.EXACTLY
                resizeHeight = heightSpecMode != MeasureSpec.EXACTLY
                desiredAspect = aspect
            }
        }

        val pLeft = paddingLeft
        val pRight = paddingRight
        val pTop = paddingTop
        val pBottom = paddingBottom

        var widthSize: Int
        var heightSize: Int

        if (resizeWidth || resizeHeight) {
            // If we get here, it means we want to resize to match the
            // drawables aspect ratio, and we have the freedom to change at
            // least one dimension.

            // Get the max possible width given our constraints
            widthSize = resolveAdjustedSize(w + pLeft + pRight, minimumWidth, maxWidth, widthMeasureSpec)

            // Get the max possible height given our constraints
            heightSize = resolveAdjustedSize(h + pTop + pBottom, minimumHeight, maxHeight, heightMeasureSpec)

            if (desiredAspect != 0.0f) {
                // See what our actual aspect ratio is
                val actualAspect = (widthSize - pLeft - pRight).toFloat() / (heightSize - pTop - pBottom)

                if (abs(actualAspect - desiredAspect) > 0.0000001) {
                    var done = false

                    // Try adjusting width to be proportional to height
                    if (resizeWidth) {
                        val newWidth = (desiredAspect * (heightSize - pTop - pBottom)).toInt() + pLeft + pRight

                        // Allow the width to outgrow its original estimate if height is fixed.
                        // if (!resizeHeight) {
                        //     widthSize = resolveAdjustedSize(newWidth, minimumWidth, maxWidth, widthMeasureSpec)
                        // }

                        if (isSizeAcceptable(newWidth, minimumWidth, maxWidth, widthMeasureSpec)) {
                            widthSize = newWidth
                            done = true
                        }
                    }

                    // Try adjusting height to be proportional to width
                    if (!done && resizeHeight) {
                        val newHeight = ((widthSize - pLeft - pRight) / desiredAspect).toInt() + pTop + pBottom

                        // Allow the height to outgrow its original estimate if width is fixed.
                        if (!resizeWidth) {
                            heightSize = resolveAdjustedSize(newHeight, minimumHeight, maxHeight, heightMeasureSpec)
                        }

                        if (isSizeAcceptable(newHeight, minimumHeight, maxHeight, heightMeasureSpec)) {
                            heightSize = newHeight
                        }
                    }
                }
            }
        } else {
            // We are either don't want to preserve the drawables aspect ratio,
            // or we are not allowed to change view dimensions. Just measure in
            // the normal way.
            w += pLeft + pRight
            h += pTop + pBottom

            w = max(w, suggestedMinimumWidth)
            h = max(h, suggestedMinimumHeight)

            widthSize = View.resolveSizeAndState(w, widthMeasureSpec, 0)
            heightSize = View.resolveSizeAndState(h, heightMeasureSpec, 0)
        }

        setMeasuredDimension(widthSize, heightSize)
    }

    companion object {
        private val MIN_ATTRS = intArrayOf(
            android.R.attr.minWidth,
            android.R.attr.minHeight,
        )

        private val ATTRS = intArrayOf(
            android.R.attr.adjustViewBounds,
            android.R.attr.maxWidth,
            android.R.attr.maxHeight,
        )
    }
}
