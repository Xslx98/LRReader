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
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import com.hippo.ehviewer.R
import com.hippo.widget.LoadImageViewNew

class FixedThumbNew @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LoadImageViewNew(context, attrs, defStyle) {

    private var minAspect: Float = 0f
    private var maxAspect: Float = 0f

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.FixedThumbNew, defStyle, 0)
        minAspect = a.getFloat(R.styleable.FixedThumb_minAspect, 0.0f)
        maxAspect = a.getFloat(R.styleable.FixedThumb_maxAspect, 0.0f)
        a.recycle()
    }

    fun setFix(minAspect: Float, maxAspect: Float) {
        this.minAspect = minAspect
        this.maxAspect = maxAspect
    }

    override fun onPreSetImageDrawable(drawable: Drawable?, isTarget: Boolean) {
        if (isTarget && drawable != null) {
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            if (width > 0 && height > 0) {
                val aspect = width.toFloat() / height.toFloat()
                if (aspect < maxAspect && aspect > minAspect) {
                    scaleType = ScaleType.CENTER_CROP
                    return
                }
            }
        }
        scaleType = ScaleType.FIT_CENTER
    }

    override fun onPreSetImageResource(resId: Int, isTarget: Boolean) {
        scaleType = ScaleType.FIT_CENTER
    }
}
