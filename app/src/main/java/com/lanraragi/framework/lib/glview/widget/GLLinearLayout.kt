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

import androidx.annotation.IntDef
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.lib.glview.view.Gravity
import kotlin.math.max

class GLLinearLayout : GLView() {

    @IntDef(HORIZONTAL, VERTICAL)
    @Retention(AnnotationRetention.SOURCE)
    annotation class OrientationMode

    var interval = 0
        set(interval) {
            if (field != interval) {
                field = interval
                requestLayout()
            }
        }

    /**
     * Should the layout be a column or a row: [HORIZONTAL] or [VERTICAL].
     * Default value is [VERTICAL].
     */
    @get:OrientationMode
    var orientation = VERTICAL
        set(@OrientationMode orientation) {
            if (field != orientation) {
                field = orientation
                requestLayout()
            }
        }

    private val tempList: MutableList<GLView> = ArrayList()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val layoutWidthSpec = MeasureSpec.makeMeasureSpec(
            MeasureSpec.getSize(widthSpec) - mPaddings.left - mPaddings.right,
            MeasureSpec.getMode(widthSpec)
        )
        val layoutHeightSpec = MeasureSpec.makeMeasureSpec(
            MeasureSpec.getSize(heightSpec) - mPaddings.top - mPaddings.bottom,
            MeasureSpec.getMode(heightSpec)
        )

        if (orientation == HORIZONTAL && MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) {
            measureWeightedRow(layoutWidthSpec, layoutHeightSpec)
        } else if (orientation == VERTICAL && MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            measureWeightedColumn(layoutWidthSpec, layoutHeightSpec)
        } else {
            measureAllComponents(this, layoutWidthSpec, layoutHeightSpec)
        }

        var maxWidth = 0
        var maxHeight = 0
        for (i in 0 until componentCount) {
            val component = getComponent(i)
            if (component.visibility == GONE) {
                continue
            }

            if (orientation == VERTICAL) {
                maxWidth = max(maxWidth, component.measuredWidth)
                if (i != 0) {
                    maxHeight += interval
                }
                maxHeight += component.measuredHeight
            } else if (orientation == HORIZONTAL) {
                maxHeight = max(maxHeight, component.measuredHeight)
                if (i != 0) {
                    maxWidth += interval
                }
                maxWidth += component.measuredWidth
            }
        }

        val paddings = mPaddings
        maxWidth = maxWidth + paddings.left + paddings.right
        maxHeight = maxHeight + paddings.top + paddings.bottom

        setMeasuredSize(getDefaultSize(maxWidth, widthSpec), getDefaultSize(maxHeight, heightSpec))
    }

    // EXACTLY-wide row: the unweighted components are measured first, the
    // weighted ones share the remaining width.
    private fun measureWeightedRow(layoutWidthSpec: Int, layoutHeightSpec: Int) {
        var width = MeasureSpec.getSize(layoutWidthSpec)
        var sumWeight = 0.0f

        for (i in 0 until componentCount) {
            val component = getComponent(i)
            if (component.visibility == GONE) {
                continue
            }
            val lp = component.layoutParams as LayoutParams

            if (lp.weight > 0.0f) {
                sumWeight += lp.weight
                tempList.add(component)
            } else {
                measureComponent(component, layoutWidthSpec, layoutHeightSpec)
                width -= component.measuredWidth
            }
        }

        for (component in tempList) {
            val lp = component.layoutParams as LayoutParams
            val componentWidthSpec = MeasureSpec.makeMeasureSpec(
                (width * lp.weight / sumWeight).toInt(), MeasureSpec.EXACTLY
            )
            val componentHeightSpec = getComponentSpec(layoutHeightSpec, lp.height)
            component.measure(componentWidthSpec, componentHeightSpec)
        }

        tempList.clear()
    }

    // EXACTLY-tall column: the unweighted components are measured first, the
    // weighted ones share the remaining height.
    private fun measureWeightedColumn(layoutWidthSpec: Int, layoutHeightSpec: Int) {
        var height = MeasureSpec.getSize(layoutHeightSpec)
        var sumWeight = 0.0f

        for (i in 0 until componentCount) {
            val component = getComponent(i)
            if (component.visibility == GONE) {
                continue
            }
            val lp = component.layoutParams as LayoutParams

            if (lp.weight > 0.0f) {
                sumWeight += lp.weight
                tempList.add(component)
            } else {
                measureComponent(component, layoutWidthSpec, layoutHeightSpec)
                height -= component.measuredHeight
            }
        }

        for (component in tempList) {
            val lp = component.layoutParams as LayoutParams
            val componentWidthSpec = getComponentSpec(layoutWidthSpec, lp.width)
            val componentHeightSpec = MeasureSpec.makeMeasureSpec(
                (height * lp.weight / sumWeight).toInt(), MeasureSpec.EXACTLY
            )
            component.measure(componentWidthSpec, componentHeightSpec)
        }

        tempList.clear()
    }

    override fun onLayout(changeSize: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = this.width
        val height = this.height
        val paddings = mPaddings
        var componentLeft: Int
        var componentTop: Int

        if (orientation == VERTICAL) {
            componentTop = paddings.top

            for (i in 0 until componentCount) {
                val component = getComponent(i)
                if (component.visibility == GONE) {
                    continue
                }
                val measureWidth = component.measuredWidth
                val measureHeight = component.measuredHeight

                val lp = component.layoutParams as LayoutParams
                componentLeft = getDefaultBegin(
                    width, measureWidth, paddings.left, paddings.right,
                    Gravity.getPosition(lp.gravity, Gravity.HORIZONTAL)
                )
                component.layout(
                    componentLeft, componentTop,
                    componentLeft + measureWidth, componentTop + measureHeight
                )

                componentTop += measureHeight + interval
            }
        } else if (orientation == HORIZONTAL) {
            componentLeft = paddings.left

            for (i in 0 until componentCount) {
                val component = getComponent(i)
                if (component.visibility == GONE) {
                    continue
                }
                val measureWidth = component.measuredWidth
                val measureHeight = component.measuredHeight

                val lp = component.layoutParams as LayoutParams
                componentTop = getDefaultBegin(
                    height, measureHeight, paddings.top, paddings.bottom,
                    Gravity.getPosition(lp.gravity, Gravity.VERTICAL)
                )
                component.layout(
                    componentLeft, componentTop,
                    componentLeft + measureWidth, componentTop + measureHeight
                )

                componentLeft += measureWidth + interval
            }
        }
    }

    override fun checkLayoutParams(p: GLView.LayoutParams?): Boolean = p is LayoutParams

    override fun generateDefaultLayoutParams(): GLView.LayoutParams =
        LayoutParams(GLView.LayoutParams.WRAP_CONTENT, GLView.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: GLView.LayoutParams): GLView.LayoutParams = LayoutParams(p)

    open class LayoutParams : GravityLayoutParams {

        @JvmField
        var weight = 0.0f

        constructor(source: GLView.LayoutParams) : super(source) {
            if (source is LayoutParams) {
                weight = source.weight
            }
        }

        constructor(width: Int, height: Int) : super(width, height)
    }

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}
