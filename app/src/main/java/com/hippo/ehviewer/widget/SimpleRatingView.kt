/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.hippo.ehviewer.R
import com.hippo.lib.yorozuya.MathUtils
import com.hippo.util.DrawableManager

/**
 * 5 stars, from 0 to 10
 */
class SimpleRatingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val mStarDrawable: Drawable
    private val mStarHalfDrawable: Drawable
    private val mStarOutlineDrawable: Drawable
    private val mRatingSize: Int
    private val mRatingInterval: Int

    private var mRating: Float = 0f
    private var mRatingInt: Int = 0

    init {
        val resources = context.resources
        mStarDrawable = DrawableManager.getVectorDrawable(context, R.drawable.v_star_x16)!!
        mStarHalfDrawable = DrawableManager.getVectorDrawable(context, R.drawable.v_star_half_x16)!!
        mStarOutlineDrawable = DrawableManager.getVectorDrawable(context, R.drawable.v_star_outline_x16)!!
        mRatingSize = resources.getDimensionPixelOffset(R.dimen.rating_size)
        mRatingInterval = resources.getDimensionPixelOffset(R.dimen.rating_interval)

        mStarDrawable.setBounds(0, 0, mRatingSize, mRatingSize)
        mStarHalfDrawable.setBounds(0, 0, mRatingSize, mRatingSize)
        mStarOutlineDrawable.setBounds(0, 0, mRatingSize, mRatingSize)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(mRatingSize * 5 + mRatingInterval * 4, mRatingSize)
    }

    override fun onDraw(canvas: Canvas) {
        var ratingInt = mRatingInt
        val step = mRatingSize + mRatingInterval
        var numStar = ratingInt / 2
        val numStarHalf = ratingInt % 2
        val saved = canvas.save()
        while (numStar-- > 0) {
            mStarDrawable.draw(canvas)
            canvas.translate(step.toFloat(), 0f)
        }
        if (numStarHalf == 1) {
            mStarHalfDrawable.draw(canvas)
            canvas.translate(step.toFloat(), 0f)
        }
        var numOutline = 5 - ratingInt / 2 - numStarHalf
        while (numOutline-- > 0) {
            mStarOutlineDrawable.draw(canvas)
            canvas.translate(step.toFloat(), 0f)
        }
        canvas.restoreToCount(saved)
    }

    fun setRating(rating: Float) {
        if (mRating != rating) {
            mRating = rating
            val ratingInt = MathUtils.clamp(Math.ceil((rating * 2).toDouble()).toInt(), 0, 10)
            if (mRatingInt != ratingInt) {
                mRatingInt = ratingInt
                invalidate()
            }
        }
    }

    fun getRating(): Float = mRating
}
