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

package com.lanraragi.framework.drawable

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

class RoundSideRectDrawable(color: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val path = Path()
    private val tempRectF = RectF()

    init {
        paint.color = color
    }

    override fun onBoundsChange(bounds: Rect) {
        val width = bounds.width()
        val height = bounds.height()
        val rectF = tempRectF

        path.reset()
        if (width > height) {
            val radius = height / 2
            path.moveTo((bounds.right - radius).toFloat(), bounds.top.toFloat())
            rectF.set(
                (bounds.right - height).toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(),
            )
            path.arcTo(rectF, -90.0f, 180.0f, false)
            path.lineTo((bounds.left + radius).toFloat(), bounds.bottom.toFloat())
            rectF.set(
                bounds.left.toFloat(), bounds.top.toFloat(),
                (bounds.left + height).toFloat(), bounds.bottom.toFloat(),
            )
            path.arcTo(rectF, 90.0f, 180.0f, false)
            path.lineTo((bounds.right - radius).toFloat(), bounds.top.toFloat())
        } else if (width == height) {
            path.addCircle(
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), (width / 2).toFloat(), Path.Direction.CW,
            )
        }
        // LEGACY: height > width case not needed in current UI layouts
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
