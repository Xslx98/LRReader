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
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hippo.android.resource.AttrResources
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.GuideSettings
import com.hippo.lib.yorozuya.ViewUtils

class GalleryGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr), View.OnClickListener {

    private val mBgColor: Int
    private val mPaint: Paint
    private val mPoints = FloatArray(3 * 4)
    private var mStep = 0

    private var mLeftText: TextView? = null
    private var mRightText: TextView? = null
    private var mMenuText: TextView? = null
    private var mProgressText: TextView? = null
    private var mLongClickText: TextView? = null

    init {
        mBgColor = AttrResources.getAttrColor(context, R.attr.guideBackgroundColor)
        mPaint = Paint().apply {
            color = AttrResources.getAttrColor(context, R.attr.guideTitleColor)
            style = Paint.Style.STROKE
            strokeWidth = context.resources.getDimension(R.dimen.gallery_guide_divider_width)
        }
        setOnClickListener(this)
        setWillNotDraw(false)
        bind()
    }

    private fun bind() {
        // Clear up
        removeAllViews()
        mLeftText = null
        mRightText = null
        mMenuText = null
        mProgressText = null
        mLongClickText = null

        when (mStep) {
            0 -> bind1()
            else -> bind2()
        }
    }

    private fun bind1() {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.widget_gallery_guide_1, this)
        mLeftText = getChildAt(0) as TextView
        mRightText = getChildAt(1) as TextView
        mMenuText = getChildAt(2) as TextView
        mProgressText = getChildAt(3) as TextView
    }

    private fun bind2() {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.widget_gallery_guide_2, this)
        mLongClickText = getChildAt(0) as TextView
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        check(MeasureSpec.EXACTLY == widthMode && MeasureSpec.EXACTLY == heightMode)

        when (mStep) {
            0 -> {
                mLeftText!!.measure(
                    MeasureSpec.makeMeasureSpec(widthSize / 3, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
                )
                mRightText!!.measure(
                    MeasureSpec.makeMeasureSpec(widthSize / 3, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
                )
                mMenuText!!.measure(
                    MeasureSpec.makeMeasureSpec(widthSize / 3, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(heightSize / 2, MeasureSpec.EXACTLY)
                )
                mProgressText!!.measure(
                    MeasureSpec.makeMeasureSpec(widthSize / 3, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(heightSize / 2, MeasureSpec.EXACTLY)
                )
            }
            else -> {
                mLongClickText!!.measure(
                    MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
                )
            }
        }

        setMeasuredDimension(widthSize, heightSize)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        when (mStep) {
            0 -> {
                mLeftText!!.layout(0, 0, width / 3, height)
                mRightText!!.layout(width * 2 / 3, 0, width, height)
                mMenuText!!.layout(width / 3, 0, width * 2 / 3, height / 2)
                mProgressText!!.layout(width / 3, height / 2, width * 2 / 3, height)
            }
            else -> {
                mLongClickText!!.layout(0, 0, width, height)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (0 == mStep) {
            mPoints[0] = (w / 3).toFloat()
            mPoints[1] = 0f
            mPoints[2] = (w / 3).toFloat()
            mPoints[3] = h.toFloat()

            mPoints[4] = (w * 2 / 3).toFloat()
            mPoints[5] = 0f
            mPoints[6] = (w * 2 / 3).toFloat()
            mPoints[7] = h.toFloat()

            mPoints[8] = (w / 3).toFloat()
            mPoints[9] = (h / 2).toFloat()
            mPoints[10] = (w * 2 / 3).toFloat()
            mPoints[11] = (h / 2).toFloat()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(mBgColor)
        if (0 == mStep) {
            canvas.drawLines(mPoints, mPaint)
        }
    }

    override fun onClick(v: View) {
        when (mStep) {
            0 -> {
                mStep++
                bind()
            }
            else -> {
                GuideSettings.putGuideGallery(false)
                ViewUtils.removeFromParent(this)
            }
        }
    }
}
