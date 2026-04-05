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
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

class ReversibleSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private var mReverse = false

    fun setReverse(reverse: Boolean) {
        mReverse = reverse
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        val reverse = mReverse
        var saveCount = 0
        if (reverse) {
            saveCount = canvas.save()
            val px = this.width / 2.0f
            val py = this.height / 2.0f
            canvas.scale(-1f, 1f, px, py)
        }
        super.draw(canvas)
        if (reverse) {
            canvas.restoreToCount(saveCount)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val reverse = mReverse
        var x = 0.0f
        var y = 0.0f
        if (reverse) {
            x = event.x
            y = event.y
            event.setLocation(width - x, y)
        }
        val result = super.onTouchEvent(event)
        if (reverse) {
            event.setLocation(x, y)
        }
        return result
    }
}
