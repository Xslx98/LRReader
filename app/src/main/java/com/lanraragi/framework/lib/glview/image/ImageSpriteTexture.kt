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

@file:Suppress("LongParameterList") // TODO(2.4-idiom)

package com.lanraragi.framework.lib.glview.image

import android.graphics.RectF
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.reader.util.AssertUtils

open class ImageSpriteTexture(image: ImageWrapper, count: Int, rects: IntArray) : ImageTexture(image) {

    val count: Int
    private val rects: IntArray

    private val tempSource = RectF()
    private val tempTarget = RectF()

    init {
        AssertUtils.assertEquals("rects.length must be count * 4", count * 4, rects.size)
        this.count = count
        this.rects = rects
    }

    fun drawSprite(canvas: GLCanvas, index: Int, x: Int, y: Int) {
        val rects = rects
        val offset = index * 4
        val sourceX = rects[offset]
        val sourceY = rects[offset + 1]
        val sourceWidth = rects[offset + 2]
        val sourceHeight = rects[offset + 3]
        tempSource.set(
            sourceX.toFloat(), sourceY.toFloat(),
            (sourceX + sourceWidth).toFloat(), (sourceY + sourceHeight).toFloat()
        )
        tempTarget.set(x.toFloat(), y.toFloat(), (x + sourceWidth).toFloat(), (y + sourceHeight).toFloat())
        draw(canvas, tempSource, tempTarget)
    }

    fun drawSprite(canvas: GLCanvas, index: Int, x: Int, y: Int, width: Int, height: Int) {
        val rects = rects
        val offset = index * 4
        val sourceX = rects[offset]
        val sourceY = rects[offset + 1]
        tempSource.set(
            sourceX.toFloat(), sourceY.toFloat(),
            (sourceX + rects[offset + 2]).toFloat(), (sourceY + rects[offset + 3]).toFloat()
        )
        tempTarget.set(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        draw(canvas, tempSource, tempTarget)
    }

    fun drawSpriteMixed(canvas: GLCanvas, index: Int, color: Int, ratio: Float, x: Int, y: Int) {
        val rects = rects
        val offset = index * 4
        val sourceX = rects[offset]
        val sourceY = rects[offset + 1]
        val sourceWidth = rects[offset + 2]
        val sourceHeight = rects[offset + 3]
        tempSource.set(
            sourceX.toFloat(), sourceY.toFloat(),
            (sourceX + sourceWidth).toFloat(), (sourceY + sourceHeight).toFloat()
        )
        tempTarget.set(x.toFloat(), y.toFloat(), (x + sourceWidth).toFloat(), (y + sourceHeight).toFloat())
        drawMixed(canvas, color, ratio, tempSource, tempTarget)
    }

    fun drawSpriteMixed(
        canvas: GLCanvas,
        index: Int,
        color: Int,
        ratio: Float,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val rects = rects
        val offset = index * 4
        val sourceX = rects[offset]
        val sourceY = rects[offset + 1]
        tempSource.set(
            sourceX.toFloat(), sourceY.toFloat(),
            (sourceX + rects[offset + 2]).toFloat(), (sourceY + rects[offset + 3]).toFloat()
        )
        tempTarget.set(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        drawMixed(canvas, color, ratio, tempSource, tempTarget)
    }
}
