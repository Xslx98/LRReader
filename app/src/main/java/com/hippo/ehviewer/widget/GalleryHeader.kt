/*
 * Copyright 2019 Hippo Seven
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
import android.os.Build
import android.util.AttributeSet
import android.view.DisplayCutout
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import com.hippo.ehviewer.R
import com.hippo.lib.yorozuya.ObjectUtils

class GalleryHeader(context: Context, attrs: AttributeSet) : ViewGroup(context, attrs) {

    private var displayCutout: DisplayCutout? = null

    private lateinit var battery: View
    private lateinit var progress: View
    private lateinit var clock: View

    private val batteryRect = Rect()
    private val progressRect = Rect()
    private val clockRect = Rect()

    private val location = IntArray(2)

    private var lastX = 0
    private var lastY = 0

    @RequiresApi(api = Build.VERSION_CODES.P)
    fun setDisplayCutout(displayCutout: DisplayCutout?) {
        if (!ObjectUtils.equal(this.displayCutout, displayCutout)) {
            this.displayCutout = displayCutout
            requestLayout()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        battery = findViewById(R.id.battery)
        progress = findViewById(R.id.progress)
        clock = findViewById(R.id.clock)
    }

    private fun measureChild(rect: Rect, view: View, width: Int, paddingLeft: Int, paddingRight: Int) {
        val lp = view.layoutParams as MarginLayoutParams
        val left = when (view) {
            battery -> paddingLeft + lp.leftMargin
            progress -> paddingLeft + (width - paddingLeft - paddingRight) / 2 - view.measuredWidth / 2
            else -> width - paddingRight - lp.rightMargin - view.measuredWidth
        }
        rect.set(left, lp.topMargin, left + view.measuredWidth, lp.topMargin + view.measuredHeight)
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    private fun offsetVertically(rect: Rect, view: View, width: Int): Boolean {
        var offset = 0

        measureChild(rect, view, width, 0, 0)
        rect.offset(lastX, lastY)

        for (notch in displayCutout!!.boundingRects) {
            if (Rect.intersects(notch, rect)) {
                offset = maxOf(offset, notch.bottom - lastY)
            }
        }

        if (offset != 0) {
            rect.offset(-lastX, -lastY)
            rect.offset(0, offset)
            return true
        } else {
            return false
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    private fun getOffsetLeft(rect: Rect, view: View, width: Int): Int {
        var offset = 0

        measureChild(rect, view, width, 0, 0)
        rect.offset(lastX, lastY)

        for (notch in displayCutout!!.boundingRects) {
            if (Rect.intersects(notch, rect)) {
                offset = maxOf(offset, notch.right - lastX)
            }
        }

        return offset
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    private fun getOffsetRight(rect: Rect, view: View, width: Int): Int {
        var offset = 0

        measureChild(rect, view, width, 0, 0)
        rect.offset(lastX, lastY)

        for (notch in displayCutout!!.boundingRects) {
            if (Rect.intersects(notch, rect)) {
                offset = maxOf(offset, lastX + width - notch.left)
            }
        }

        return offset
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        check(MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY)
        val width = MeasureSpec.getSize(widthMeasureSpec)

        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val lp = child.layoutParams as MarginLayoutParams
            height = maxOf(height, child.measuredHeight + lp.topMargin)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && displayCutout != null) {
            // Check progress covered
            if (offsetVertically(progressRect, progress, width)) {
                offsetVertically(batteryRect, battery, width)
                offsetVertically(clockRect, clock, width)
                height = maxOf(progressRect.bottom, maxOf(batteryRect.bottom, clockRect.bottom))
            } else {
                // Clamp left and right
                val pl = getOffsetLeft(batteryRect, battery, width)
                val pr = getOffsetRight(clockRect, clock, width)
                measureChild(batteryRect, battery, width, pl, pr)
                measureChild(progressRect, progress, width, pl, pr)
                measureChild(clockRect, clock, width, pl, pr)
            }
        } else {
            measureChild(batteryRect, battery, width, 0, 0)
            measureChild(progressRect, progress, width, 0, 0)
            measureChild(clockRect, clock, width, 0, 0)
        }

        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        battery.layout(batteryRect.left, batteryRect.top, batteryRect.right, batteryRect.bottom)
        progress.layout(progressRect.left, progressRect.top, progressRect.right, progressRect.bottom)
        clock.layout(clockRect.left, clockRect.top, clockRect.right, clockRect.bottom)

        getLocationOnScreen(location)
        if (lastX != location[0] || lastY != location[1]) {
            lastX = location[0]
            lastY = location[1]
            requestLayout()
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet): MarginLayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun checkLayoutParams(p: LayoutParams): Boolean {
        return p is MarginLayoutParams
    }

    override fun generateLayoutParams(lp: LayoutParams): MarginLayoutParams {
        return if (lp is MarginLayoutParams) {
            MarginLayoutParams(lp)
        } else {
            MarginLayoutParams(lp)
        }
    }
}
