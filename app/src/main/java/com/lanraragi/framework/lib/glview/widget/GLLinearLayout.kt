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

@file:Suppress("CyclomaticComplexMethod", "LongMethod") // TODO(2.4-idiom)

package com.lanraragi.framework.lib.glview.widget

import androidx.annotation.IntDef
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.lib.glview.view.Gravity
import kotlin.math.max

class GLLinearLayout : GLView() {

    @IntDef(HORIZONTAL, VERTICAL)
    @Retention(AnnotationRetention.SOURCE)
    annotation class OrientationMode

    private var mInterval = 0
    private var mOrientation = VERTICAL

    private val mTempList: MutableList<GLView> = ArrayList()

    fun setInterval(interval: Int) {
        if (mInterval != interval) {
            mInterval = interval
            requestLayout()
        }
    }

    /**
     * Should the layout be a column or a row.
     * @param orientation Pass [HORIZONTAL] or [VERTICAL]. Default
     * value is [HORIZONTAL].
     */
    fun setOrientation(@OrientationMode orientation: Int) {
        if (mOrientation != orientation) {
            mOrientation = orientation
            requestLayout()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val layoutWidthSpec = MeasureSpec.makeMeasureSpec(
            MeasureSpec.getSize(widthSpec) - mPaddings.left - mPaddings.right,
            MeasureSpec.getMode(widthSpec)
        )
        val layoutHeightSpec = MeasureSpec.makeMeasureSpec(
            MeasureSpec.getSize(heightSpec) - mPaddings.top - mPaddings.bottom,
            MeasureSpec.getMode(heightSpec)
        )

        if (mOrientation == HORIZONTAL && MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) {
            var width = MeasureSpec.getSize(layoutWidthSpec)
            var sumWeight = 0.0f

            for (i in 0 until componentCount) {
                val component = getComponent(i)
                if (component.getVisibility() == GONE) {
                    continue
                }
                val lp = component.getLayoutParams() as LayoutParams

                if (lp.weight > 0.0f) {
                    sumWeight += lp.weight
                    mTempList.add(component)
                } else {
                    measureComponent(component, layoutWidthSpec, layoutHeightSpec)
                    width -= component.getMeasuredWidth()
                }
            }

            for (component in mTempList) {
                val lp = component.getLayoutParams() as LayoutParams
                val componentWidthSpec = MeasureSpec.makeMeasureSpec(
                    (width * lp.weight / sumWeight).toInt(), MeasureSpec.EXACTLY
                )
                val componentHeightSpec = getComponentSpec(layoutHeightSpec, lp.height)
                component.measure(componentWidthSpec, componentHeightSpec)
            }

            mTempList.clear()
        } else if (mOrientation == VERTICAL && MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            var height = MeasureSpec.getSize(layoutHeightSpec)
            var sumWeight = 0.0f

            for (i in 0 until componentCount) {
                val component = getComponent(i)
                if (component.getVisibility() == GONE) {
                    continue
                }
                val lp = component.getLayoutParams() as LayoutParams

                if (lp.weight > 0.0f) {
                    sumWeight += lp.weight
                    mTempList.add(component)
                } else {
                    measureComponent(component, layoutWidthSpec, layoutHeightSpec)
                    height -= component.getMeasuredHeight()
                }
            }

            for (component in mTempList) {
                val lp = component.getLayoutParams() as LayoutParams
                val componentWidthSpec = getComponentSpec(layoutWidthSpec, lp.width)
                val componentHeightSpec = MeasureSpec.makeMeasureSpec(
                    (height * lp.weight / sumWeight).toInt(), MeasureSpec.EXACTLY
                )
                component.measure(componentWidthSpec, componentHeightSpec)
            }

            mTempList.clear()
        } else {
            measureAllComponents(this, layoutWidthSpec, layoutHeightSpec)
        }

        var maxWidth = 0
        var maxHeight = 0
        for (i in 0 until componentCount) {
            val component = getComponent(i)
            if (component.getVisibility() == GONE) {
                continue
            }

            if (mOrientation == VERTICAL) {
                maxWidth = max(maxWidth, component.getMeasuredWidth())
                if (i != 0) {
                    maxHeight += mInterval
                }
                maxHeight += component.getMeasuredHeight()
            } else if (mOrientation == HORIZONTAL) {
                maxHeight = max(maxHeight, component.getMeasuredHeight())
                if (i != 0) {
                    maxWidth += mInterval
                }
                maxWidth += component.getMeasuredWidth()
            }
        }

        val paddings = getPaddings()
        maxWidth = maxWidth + paddings.left + paddings.right
        maxHeight = maxHeight + paddings.top + paddings.bottom

        setMeasuredSize(getDefaultSize(maxWidth, widthSpec), getDefaultSize(maxHeight, heightSpec))
    }

    override fun onLayout(changeSize: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = width
        val height = height
        val paddings = getPaddings()
        var componentLeft: Int
        var componentTop: Int

        if (mOrientation == VERTICAL) {
            componentTop = paddings.top

            for (i in 0 until componentCount) {
                val component = getComponent(i)
                if (component.getVisibility() == GONE) {
                    continue
                }
                val measureWidth = component.getMeasuredWidth()
                val measureHeight = component.getMeasuredHeight()

                val lp = component.getLayoutParams() as LayoutParams
                componentLeft = getDefaultBegin(
                    width, measureWidth, paddings.left, paddings.right,
                    Gravity.getPosition(lp.gravity, Gravity.HORIZONTAL)
                )
                component.layout(
                    componentLeft, componentTop,
                    componentLeft + measureWidth, componentTop + measureHeight
                )

                componentTop += measureHeight + mInterval
            }
        } else if (mOrientation == HORIZONTAL) {
            componentLeft = paddings.left

            for (i in 0 until componentCount) {
                val component = getComponent(i)
                if (component.getVisibility() == GONE) {
                    continue
                }
                val measureWidth = component.getMeasuredWidth()
                val measureHeight = component.getMeasuredHeight()

                val lp = component.getLayoutParams() as LayoutParams
                componentTop = getDefaultBegin(
                    height, measureHeight, paddings.top, paddings.bottom,
                    Gravity.getPosition(lp.gravity, Gravity.VERTICAL)
                )
                component.layout(
                    componentLeft, componentTop,
                    componentLeft + measureWidth, componentTop + measureHeight
                )

                componentLeft += measureWidth + mInterval
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
