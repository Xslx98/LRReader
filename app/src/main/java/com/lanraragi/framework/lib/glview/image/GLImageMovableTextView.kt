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

import android.text.TextUtils
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.lib.glview.view.Gravity
import kotlin.math.max

class GLImageMovableTextView : GLView() {

    private var textTexture: ImageMovableTextTexture? = null

    private var text = ""
    private var indexes = EMPTY_INDEXES

    private var gravity = Gravity.NO_GRAVITY

    private fun generateIndexes() {
        val texture = textTexture
        if (texture == null || TextUtils.isEmpty(text)) {
            indexes = EMPTY_INDEXES
        } else {
            indexes = texture.getTextIndexes(text)
        }
    }

    fun setTextTexture(textTexture: ImageMovableTextTexture?) {
        if (this.textTexture === textTexture) {
            return
        }
        this.textTexture = textTexture

        generateIndexes()
        requestLayout()
    }

    fun setText(text: String?) {
        val newText = text.orEmpty()
        if (newText == this.text) {
            return
        }
        this.text = newText

        generateIndexes()
        requestLayout()
    }

    fun setGravity(gravity: Int) {
        if (this.gravity != gravity) {
            this.gravity = gravity
            invalidate()
        }
    }

    override fun getSuggestedMinimumWidth(): Int {
        val texture = textTexture
        if (texture == null) {
            return super.getSuggestedMinimumWidth()
        } else {
            return max(
                texture.getTextWidth(indexes).toInt() + mPaddings.left + mPaddings.right,
                super.getSuggestedMinimumWidth()
            )
        }
    }

    override fun getSuggestedMinimumHeight(): Int {
        val texture = textTexture
        if (texture == null) {
            return super.getSuggestedMinimumHeight()
        } else {
            return max(
                texture.textHeight.toInt() + mPaddings.top + mPaddings.bottom,
                super.getSuggestedMinimumHeight()
            )
        }
    }

    override fun onRender(canvas: GLCanvas) {
        // indexes is never null in Kotlin, so only the texture is checked.
        val texture = textTexture ?: return

        val paddings = getPaddings()
        val x = getDefaultBegin(
            width, texture.getTextWidth(indexes).toInt(),
            paddings.left, paddings.right, Gravity.getPosition(gravity, Gravity.HORIZONTAL)
        )
        val y = getDefaultBegin(
            height, texture.textHeight.toInt(),
            paddings.top, paddings.bottom, Gravity.getPosition(gravity, Gravity.VERTICAL)
        )
        texture.drawText(canvas, indexes, x, y)
    }

    companion object {
        private val EMPTY_INDEXES = IntArray(0)
    }
}
