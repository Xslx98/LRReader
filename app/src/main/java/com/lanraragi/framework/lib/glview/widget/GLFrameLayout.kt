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

package com.lanraragi.framework.lib.glview.widget

import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.lib.glview.view.Gravity
import kotlin.math.max

open class GLFrameLayout : GLView() {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        var maxWidth = 0
        var maxHeight = 0
        for (i in 0 until componentCount) {
            val component = getComponent(i)
            if (component.getVisibility() == GONE) {
                continue
            }
            measureComponent(component, widthSpec, heightSpec)
            maxWidth = max(maxWidth, component.getMeasuredWidth())
            maxHeight = max(maxHeight, component.getMeasuredHeight())
        }

        // Consider min
        maxWidth = max(maxWidth, getSuggestedMinimumWidth())
        maxHeight = max(maxHeight, getSuggestedMinimumHeight())

        // The final
        maxWidth = getDefaultSize(maxWidth, widthSpec)
        maxHeight = getDefaultSize(maxHeight, heightSpec)

        setMeasuredSize(maxWidth, maxHeight)

        // Compares the sizes (not the modes) with EXACTLY, as the Java code
        // did, so the components are measured again for any real size.
        if (MeasureSpec.getSize(widthSpec) != MeasureSpec.EXACTLY ||
            MeasureSpec.getSize(heightSpec) != MeasureSpec.EXACTLY
        ) {
            // Measure again
            measureAllComponents(
                this,
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changeSize: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = width
        val height = height

        for (i in 0 until componentCount) {
            val component = getComponent(i)
            if (component.getVisibility() == GONE) {
                continue
            }
            val measureWidth = component.getMeasuredWidth()
            val measureHeight = component.getMeasuredHeight()
            val componentLeft: Int
            val componentTop: Int

            val lp = component.getLayoutParams() as GravityLayoutParams
            val gravity = lp.gravity
            if (Gravity.centerHorizontal(gravity)) {
                componentLeft = (width / 2) - (measureWidth / 2)
            } else if (Gravity.right(gravity)) {
                componentLeft = width - measureWidth
            } else {
                componentLeft = 0
            }
            if (Gravity.centerVertical(gravity)) {
                componentTop = (height / 2) - (measureHeight / 2)
            } else if (Gravity.bottom(gravity)) {
                componentTop = height - measureHeight
            } else {
                componentTop = 0
            }

            component.layout(
                componentLeft, componentTop,
                componentLeft + measureWidth, componentTop + measureHeight
            )
        }
    }

    override fun checkLayoutParams(p: GLView.LayoutParams?): Boolean = p is GravityLayoutParams

    override fun generateDefaultLayoutParams(): LayoutParams =
        GravityLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: GLView.LayoutParams): LayoutParams = GravityLayoutParams(p)
}
