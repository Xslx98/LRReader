/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.lanraragi.framework.lib.glview.glrenderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint.FontMetricsInt
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil

// StringTexture is a texture shows the content of a specified String.
//
// To create a StringTexture, use the newInstance() method and specify
// the String, the font size, and the color.
class StringTexture private constructor(
    private val text: String,
    private val paint: TextPaint,
    private val metrics: FontMetricsInt,
    width: Int,
    height: Int,
) : CanvasTexture(width, height) {

    override fun onDraw(canvas: Canvas, backing: Bitmap) {
        canvas.translate(0f, (-metrics.ascent).toFloat())
        canvas.drawText(text, 0f, 0f, paint)
    }

    companion object {
        fun getDefaultPaint(textSize: Float, color: Int): TextPaint {
            val paint = TextPaint()
            paint.textSize = textSize
            paint.isAntiAlias = true
            paint.color = color
            paint.setShadowLayer(2f, 0f, 0f, Color.BLACK)
            return paint
        }

        @JvmStatic
        fun newInstance(text: String, textSize: Float, color: Int): StringTexture = newInstance(text, getDefaultPaint(textSize, color))

        fun newInstance(
            text: String,
            textSize: Float,
            color: Int,
            lengthLimit: Float,
            isBold: Boolean,
        ): StringTexture {
            var shown = text
            val paint = getDefaultPaint(textSize, color)
            if (isBold) {
                paint.typeface = Typeface.defaultFromStyle(Typeface.BOLD)
            }
            if (lengthLimit > 0) {
                shown = TextUtils.ellipsize(shown, paint, lengthLimit, TextUtils.TruncateAt.END).toString()
            }
            return newInstance(shown, paint)
        }

        private fun newInstance(text: String, paint: TextPaint): StringTexture {
            val metrics = paint.fontMetricsInt
            var width = ceil(paint.measureText(text).toDouble()).toInt()
            var height = metrics.bottom - metrics.top
            // The texture size needs to be at least 1x1.
            if (width <= 0) width = 1
            if (height <= 0) height = 1
            return StringTexture(text, paint, metrics, width, height)
        }
    }
}
