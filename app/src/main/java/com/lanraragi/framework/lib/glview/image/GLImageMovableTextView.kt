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

    private var mTextTexture: ImageMovableTextTexture? = null

    private var mText = ""
    private var mIndexes = EMPTY_INDEXES

    private var mGravity = Gravity.NO_GRAVITY

    private fun generateIndexes() {
        val textTexture = mTextTexture
        if (textTexture == null || TextUtils.isEmpty(mText)) {
            mIndexes = EMPTY_INDEXES
        } else {
            mIndexes = textTexture.getTextIndexes(mText)
        }
    }

    fun setTextTexture(textTexture: ImageMovableTextTexture?) {
        if (mTextTexture === textTexture) {
            return
        }
        mTextTexture = textTexture

        generateIndexes()
        requestLayout()
    }

    fun setText(text: String?) {
        val newText = text.orEmpty()
        if (newText == mText) {
            return
        }
        mText = newText

        generateIndexes()
        requestLayout()
    }

    fun setGravity(gravity: Int) {
        if (mGravity != gravity) {
            mGravity = gravity
            invalidate()
        }
    }

    override fun getSuggestedMinimumWidth(): Int {
        val textTexture = mTextTexture
        if (textTexture == null) {
            return super.getSuggestedMinimumWidth()
        } else {
            return max(
                textTexture.getTextWidth(mIndexes).toInt() + mPaddings.left + mPaddings.right,
                super.getSuggestedMinimumWidth()
            )
        }
    }

    override fun getSuggestedMinimumHeight(): Int {
        val textTexture = mTextTexture
        if (textTexture == null) {
            return super.getSuggestedMinimumHeight()
        } else {
            return max(
                textTexture.getTextHeight().toInt() + mPaddings.top + mPaddings.bottom,
                super.getSuggestedMinimumHeight()
            )
        }
    }

    override fun onRender(canvas: GLCanvas) {
        // mIndexes is never null in Kotlin, so only the texture is checked.
        val textTexture = mTextTexture ?: return

        val paddings = getPaddings()
        val x = getDefaultBegin(
            width, textTexture.getTextWidth(mIndexes).toInt(),
            paddings.left, paddings.right, Gravity.getPosition(mGravity, Gravity.HORIZONTAL)
        )
        val y = getDefaultBegin(
            height, textTexture.getTextHeight().toInt(),
            paddings.top, paddings.bottom, Gravity.getPosition(mGravity, Gravity.VERTICAL)
        )
        textTexture.drawText(canvas, mIndexes, x, y)
    }

    companion object {
        private val EMPTY_INDEXES = IntArray(0)
    }
}
