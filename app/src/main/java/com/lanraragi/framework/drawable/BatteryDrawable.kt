/*
 * Copyright (C) 2014 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lanraragi.framework.drawable

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.lanraragi.reader.util.MathUtils

class BatteryDrawable : Drawable() {

    private var color = Color.WHITE
    private var warningColor = Color.RED
    private var elect = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private val topRect = Rect()
    private val bottomRect = Rect()
    private val rightRect = Rect()
    private val headRect = Rect()
    private val electRect = Rect()
    private var start = 0
    private var stop = 0

    init {
        paint.style = Paint.Style.FILL
        updatePaint()
    }

    override fun onBoundsChange(bounds: Rect) {
        val width = bounds.width()
        val height = bounds.height()
        val strokeWidth = (Math.sqrt((width * width + height * height).toDouble()) * 0.06f).toInt()
        val turn1 = width * 6 / 7
        val turn2 = height / 3
        val secBottom = height - strokeWidth
        start = strokeWidth
        stop = turn1 - strokeWidth

        topRect.set(0, 0, turn1, strokeWidth)
        bottomRect.set(0, secBottom, turn1, height)
        rightRect.set(turn1 - strokeWidth, strokeWidth, turn1, secBottom)
        headRect.set(turn1, turn2, width, height - turn2)
        electRect.set(0, strokeWidth, stop, secBottom)
    }

    /**
     * How to draw:<br>
     * |------------------------------|<br>
     * |\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\|<br>
     * |------------------------------|---|<br>
     * |/////////////////|         |//|\\\|<br>
     * |/////////////////|         |//|\\\|<br>
     * |------------------------------|---|<br>
     * |\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\|<br>
     * |------------------------------|
     */
    override fun draw(canvas: Canvas) {
        if (elect == -1) {
            return
        }

        electRect.right = MathUtils.lerp(start, stop, elect / 100.0f)

        canvas.drawRect(topRect, paint)
        canvas.drawRect(bottomRect, paint)
        canvas.drawRect(rightRect, paint)
        canvas.drawRect(headRect, paint)
        canvas.drawRect(electRect, paint)
    }

    private fun isWarn(): Boolean = elect <= WARN_LIMIT

    fun setColor(color: Int) {
        if (this.color == color) {
            return
        }

        this.color = color
        if (!isWarn()) {
            paint.color = color
            invalidateSelf()
        }
    }

    fun setWarningColor(color: Int) {
        if (warningColor == color) {
            return
        }

        warningColor = color
        if (isWarn()) {
            paint.color = warningColor
            invalidateSelf()
        }
    }

    fun setElect(elect: Int) {
        if (this.elect == elect) {
            return
        }

        this.elect = elect
        updatePaint()
    }

    fun setElect(elect: Int, warn: Boolean) {
        if (this.elect == elect) {
            return
        }

        this.elect = elect
        updatePaint(warn)
    }

    private fun updatePaint(warn: Boolean = isWarn()) {
        paint.color = if (warn) warningColor else color
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        paint.colorFilter = cf
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val WARN_LIMIT = 15
    }
}
