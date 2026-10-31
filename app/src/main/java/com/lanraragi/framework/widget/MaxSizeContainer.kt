/*
 * Copyright 2016 Hippo Seven
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
import android.util.AttributeSet
import android.view.ViewGroup
import com.lanraragi.reader.R
import com.lanraragi.reader.util.AssertUtils
import kotlin.math.min

class MaxSizeContainer : ViewGroup {

    private var maxWidth = 0
    private var maxHeight = 0

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    private fun init(context: Context, attrs: AttributeSet?) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.MaxSizeContainer)
        maxWidth = a.getDimensionPixelOffset(R.styleable.MaxSizeContainer_maxWidth, -1)
        maxHeight = a.getDimensionPixelOffset(R.styleable.MaxSizeContainer_maxHeight, -1)
        a.recycle()
    }

    private fun getMeasureSpec(measureSpec: Int, max: Int): Int {
        if (max < 0) {
            return measureSpec
        }

        var size = MeasureSpec.getSize(measureSpec)
        var mode = MeasureSpec.getMode(measureSpec)

        when (mode) {
            MeasureSpec.AT_MOST -> size = min(size, max)
            MeasureSpec.UNSPECIFIED -> {
                size = max
                mode = MeasureSpec.AT_MOST
            }
            MeasureSpec.EXACTLY -> Unit // An exact size stays as it is
        }
        return MeasureSpec.makeMeasureSpec(size, mode)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        AssertUtils.assertEquals("getChildCount() must be 1", 1, childCount)

        val child = getChildAt(0)
        if (child.visibility != GONE) {
            child.measure(getMeasureSpec(widthMeasureSpec, maxWidth), getMeasureSpec(heightMeasureSpec, maxHeight))
            setMeasuredDimension(child.measuredWidth, child.measuredHeight)
        } else {
            setMeasuredDimension(0, 0)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        AssertUtils.assertEquals("getChildCount() must be 1", 1, childCount)

        val child = getChildAt(0)
        if (child.visibility != GONE) {
            child.layout(0, 0, r - l, b - t)
        }
    }
}
