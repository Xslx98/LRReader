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
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.widget.LinearLayout
import com.hippo.drawerlayout.DrawerLayoutChild

class EhNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), DrawerLayoutChild {

    private var mPaint: Paint? = null
    private var mWindowPaddingTop = 0
    private var mWindowPaddingBottom = 0

    init {
        if (DRAW_SCRIM) {
            setWillNotDraw(false)
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        if (DRAW_SCRIM && mWindowPaddingTop > 0) {
            if (mPaint == null) {
                mPaint = Paint().apply {
                    color = SCRIM_COLOR
                }
            }
            canvas.drawRect(0f, 0f, width.toFloat(), mWindowPaddingTop.toFloat(), mPaint!!)
        }
    }

    override fun onGetWindowPadding(top: Int, bottom: Int) {
        mWindowPaddingTop = top
        mWindowPaddingBottom = bottom
    }

    override fun getAdditionalTopMargin(): Int = 0

    override fun getAdditionalBottomMargin(): Int = mWindowPaddingBottom

    companion object {
        private const val SCRIM_COLOR = 0x44000000
        private val DRAW_SCRIM = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }
}
