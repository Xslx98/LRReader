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

package com.hippo.ehviewer.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.hippo.ehviewer.R

class DividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val mPaint: Paint
    private val mRect = Rect()
    private val mDividerWidth: Int
    private val mDividerHeight: Int

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.DividerView)
        val color = a.getColor(R.styleable.DividerView_dividerColor, Color.BLACK)
        mDividerWidth = a.getDimensionPixelOffset(R.styleable.DividerView_dividerWidth, 0)
        mDividerHeight = a.getDimensionPixelOffset(R.styleable.DividerView_dividerHeight, 0)
        a.recycle()

        mPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            this.color = color
        }
    }

    override fun getSuggestedMinimumWidth(): Int {
        return maxOf(paddingLeft + paddingRight + mDividerWidth, super.getSuggestedMinimumWidth())
    }

    override fun getSuggestedMinimumHeight(): Int {
        return maxOf(paddingTop + paddingBottom + mDividerHeight, super.getSuggestedMinimumHeight())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = mRect
        rect.set(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        if (!rect.isEmpty) {
            canvas.drawRect(rect, mPaint)
        }
    }
}
