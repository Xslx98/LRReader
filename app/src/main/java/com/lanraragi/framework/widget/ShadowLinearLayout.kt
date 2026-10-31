/*
 * Copyright 2015 Hippo Seven
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

package com.lanraragi.framework.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.NinePatchDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import com.lanraragi.reader.R
import com.lanraragi.reader.util.ViewUtils

class ShadowLinearLayout : LinearLayout {

    private var mShadow: NinePatchDrawable? = null
    private val mShadowPaddings = Rect()

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    private fun init(context: Context) {
        // LEGACY (upstream): only 2dp shadow supported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            outlineProvider = ViewOutlineProvider.BOUNDS
            elevation = ViewUtils.dp2pix(context, 2f).toFloat()
        } else {
            // LEGACY (upstream): dark theme shadow not implemented
            setShadow(context.resources.getDrawable(R.drawable.shadow_2dp) as NinePatchDrawable)
        }
    }

    private fun setShadow(shadow: NinePatchDrawable) {
        mShadow = shadow
        shadow.getPadding(mShadowPaddings)
        updateShadowBounds()
        setWillNotDraw(false)
    }

    private fun updateShadowBounds() {
        val shadow = mShadow ?: return

        val width = width
        val height = height
        val paddings = mShadowPaddings
        if (width != 0 && height != 0) {
            shadow.setBounds(-paddings.left, -paddings.top, width + paddings.right, height + paddings.bottom)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateShadowBounds()
    }

    override fun draw(canvas: Canvas) {
        mShadow?.draw(canvas)
        super.draw(canvas)
    }
}
