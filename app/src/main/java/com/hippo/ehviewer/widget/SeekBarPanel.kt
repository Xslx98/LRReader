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
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import com.hippo.ehviewer.R
import com.hippo.lib.yorozuya.ViewUtils

class SeekBarPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var mSeekBar: SeekBar? = null
    private val mLocation = IntArray(2)

    override fun onFinishInflate() {
        super.onFinishInflate()
        mSeekBar = ViewUtils.`$$`(this, R.id.seek_bar) as SeekBar
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val seekBar = mSeekBar ?: return super.onTouchEvent(event)
        ViewUtils.getLocationInAncestor(seekBar, mLocation, this)
        val offsetX = -mLocation[0].toFloat()
        val offsetY = -mLocation[1].toFloat()
        event.offsetLocation(offsetX, offsetY)
        seekBar.onTouchEvent(event)
        event.offsetLocation(-offsetX, -offsetY)
        return true
    }

    @Suppress("DEPRECATION")
    override fun fitSystemWindows(insets: Rect): Boolean {
        insets.top = 0
        return super.fitSystemWindows(insets)
    }
}
