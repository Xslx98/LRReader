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

package com.lanraragi.reader.widget.list

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The [FastScroller] handle: a stadium (half circles on a rectangle) when
 * taller than wide, else a rectangle. Ported to Kotlin from seven332's
 * EasyRecyclerView library (0.1.1).
 */
class HandlerDrawable : Drawable() {

    private var color = Color.BLACK
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@HandlerDrawable.color
        style = Paint.Style.FILL
    }
    private val temp = RectF()

    override fun draw(canvas: Canvas) {
        val width = bounds.width()
        val height = bounds.height()
        if (width > height) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        } else {
            temp.set(0f, 0f, width.toFloat(), width.toFloat())
            canvas.drawArc(temp, -180f, 180f, true, paint)
            temp.set(0f, (height - width).toFloat(), width.toFloat(), height.toFloat())
            canvas.drawArc(temp, 0f, 180f, true, paint)
            val halfWidth = width / 2.0f
            canvas.drawRect(0f, halfWidth, width.toFloat(), height - halfWidth, paint)
        }
    }

    fun setColor(color: Int) {
        if (this.color != color) {
            this.color = color
            paint.color = color
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = when (Color.alpha(color)) {
        0xff -> PixelFormat.OPAQUE
        0x00 -> PixelFormat.TRANSPARENT
        else -> PixelFormat.TRANSLUCENT
    }
}
