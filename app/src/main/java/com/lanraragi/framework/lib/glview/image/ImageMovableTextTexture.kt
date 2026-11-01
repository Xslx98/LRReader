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

package com.lanraragi.framework.lib.glview.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.image.Image
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

class ImageMovableTextTexture(
    image: ImageWrapper,
    count: Int,
    rects: IntArray,
    characters: CharArray,
    widths: FloatArray,
    height: Float,
    maxWidth: Float,
) : ImageSpriteTexture(image, count, rects) {

    private val mCharacters: CharArray = characters
    private val mWidths: FloatArray = widths
    private val mHeight: Float = height
    private val mMaxWidth: Float = maxWidth

    fun getTextIndexes(text: String): IntArray {
        val characters = mCharacters

        val length = text.length
        val indexes = IntArray(length)
        for (i in 0 until length) {
            val ch = text[i]
            indexes[i] = Arrays.binarySearch(characters, ch)
        }

        return indexes
    }

    fun getTextWidth(text: String): Float {
        val characters = mCharacters
        val widths = mWidths
        var width = 0.0f

        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            val index = Arrays.binarySearch(characters, ch)
            if (index >= 0) {
                width += widths[index]
            } else {
                width += mMaxWidth
            }
            i++
        }

        return width
    }

    fun getTextWidth(indexes: IntArray): Float {
        val widths = mWidths
        var width = 0.0f
        val length = indexes.size
        for (i in 0 until length) {
            val index = indexes[i]
            if (index >= 0) {
                width += widths[index]
            } else {
                width += mMaxWidth
            }
        }

        return width
    }

    fun getMaxWidth(): Float {
        return mMaxWidth
    }

    fun getTextHeight(): Float {
        return mHeight
    }

    fun drawText(canvas: GLCanvas, text: String, x: Int, y: Int) {
        val characters = mCharacters
        val widths = mWidths
        var drawX = x

        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            val index = Arrays.binarySearch(characters, ch)
            if (index >= 0) {
                drawSprite(canvas, index, drawX, y)
                drawX = (drawX + widths[index]).toInt()
            } else {
                drawX = (drawX + mMaxWidth).toInt()
            }
            i++
        }
    }

    fun drawText(canvas: GLCanvas, indexes: IntArray, x: Int, y: Int) {
        val widths = mWidths
        var drawX = x

        var i = 0
        val n = indexes.size
        while (i < n) {
            val index = indexes[i]
            if (index >= 0) {
                drawSprite(canvas, index, drawX, y)
                drawX = (drawX + widths[index]).toInt()
            } else {
                drawX = (drawX + mMaxWidth).toInt()
            }
            i++
        }
    }

    companion object {
        /**
         * Create a TextTexture to draw text
         *
         * @param typeface   the typeface
         * @param size       text size
         * @param characters all Characters
         * @return the TextTexture
         */
        @JvmStatic
        fun create(typeface: Typeface?, size: Int, color: Int, characters: CharArray): ImageMovableTextTexture? {
            val paint = Paint()
            paint.isAntiAlias = true
            paint.textSize = size.toFloat()
            paint.color = color
            paint.setTypeface(typeface)

            val fmi = paint.fontMetricsInt
            val fixed = fmi.bottom
            val height = fmi.bottom - fmi.top

            val length = characters.size
            val widths = FloatArray(length)
            paint.getTextWidths(characters, 0, length, widths)

            // Calculate bitmap size
            var maxWidth = 0.0f
            for (f in widths) {
                maxWidth = max(maxWidth, f)
            }
            var hCount = ceil(sqrt((height / maxWidth * length).toDouble())).toInt()
            var vCount = ceil(sqrt((maxWidth / height * length).toDouble())).toInt()
            if (hCount * (vCount - 1) > length) {
                vCount--
            }
            if ((hCount - 1) * vCount > length) {
                hCount--
            }

            val bitmap = Bitmap.createBitmap(
                ceil((hCount * maxWidth).toDouble()).toInt(),
                ceil((vCount * height).toDouble()).toInt(), Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.translate(0f, (height - fixed).toFloat())

            // Draw
            val rects = IntArray(length * 4)
            var x = 0
            var y = 0
            for (i in 0 until length) {
                val offset = i * 4
                rects[offset] = x
                rects[offset + 1] = y
                rects[offset + 2] = widths[i].toInt()
                rects[offset + 3] = height

                canvas.drawText(characters, i, 1, x.toFloat(), y.toFloat(), paint)

                if (i % hCount == hCount - 1) {
                    // The end of row
                    x = 0
                    y += height
                } else {
                    // Java's int += float: the sum is truncated back to an int.
                    x = (x + maxWidth).toInt()
                }
            }

            val image = Image.create(bitmap) ?: return null
            val imageWrapper = ImageWrapper(image)
            return if (imageWrapper.obtain()) {
                ImageMovableTextTexture(
                    imageWrapper, length, rects, characters, widths, height.toFloat(), maxWidth
                )
            } else {
                null
            }
        }
    }
}
