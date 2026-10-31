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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ListView
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.withTranslation
import com.lanraragi.reader.R

class IndicatingListView : ListView {

    private var indicatorHeight = 0

    private val paint = Paint()
    private val tempRect = Rect()

    constructor(context: Context) : super(context) {
        init(context, null)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    private fun init(context: Context, attrs: AttributeSet?) {
        context.withStyledAttributes(attrs, R.styleable.Indicating) {
            indicatorHeight = getDimensionPixelOffset(R.styleable.Indicating_indicatorHeight, 1)
            paint.color = getColor(R.styleable.Indicating_indicatorColor, Color.BLACK)
            paint.style = Paint.Style.FILL
        }
    }

    private fun fillTopIndicatorDrawRect() {
        tempRect.set(0, 0, width, indicatorHeight)
    }

    private fun fillBottomIndicatorDrawRect() {
        tempRect.set(0, height - indicatorHeight, width, height)
    }

    private fun needShowTopIndicator(): Boolean = canScrollVertically(-1)

    private fun needShowBottomIndicator(): Boolean = canScrollVertically(1)

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        canvas.withTranslation(scrollX.toFloat(), scrollY.toFloat()) {
            // Draw top indicator
            if (needShowTopIndicator()) {
                fillTopIndicatorDrawRect()
                drawRect(tempRect, paint)
            }
            // Draw bottom indicator
            if (needShowBottomIndicator()) {
                fillBottomIndicatorDrawRect()
                drawRect(tempRect, paint)
            }
        }
    }
}
