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

import android.graphics.Color
import android.graphics.Path
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import androidx.core.view.animation.PathInterpolatorCompat
import com.lanraragi.framework.lib.glview.anim.Animation
import com.lanraragi.framework.lib.glview.anim.FloatAnimation
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.glrenderer.GLPaint
import com.lanraragi.framework.lib.glview.view.AnimationTime
import com.lanraragi.framework.lib.glview.view.GLView
import kotlin.math.max
import kotlin.math.min

class GLProgressView : GLView() {

    private val mGLPaint: GLPaint

    private var mCx = 0f
    private var mCy = 0f
    private var mRadiusX = 0f
    private var mRadiusY = 0f

    private var mTrimStart = 0.0f
    private var mTrimEnd = 0.0f
    private var mTrimOffset = 0.0f
    private var mTrimRotation = 0.0f

    private var mIndeterminate = false

    private val mAnimations: MutableList<Animation>

    init {
        mGLPaint = GLPaint()
        mGLPaint.color = Color.WHITE
        mGLPaint.backgroundColor = Color.BLACK
        mAnimations = ArrayList()

        setupAnimations()
    }

    fun setupAnimations() {
        val trimStart = object : FloatAnimation() {
            override fun onCalculate(progress: Float) {
                super.onCalculate(progress)
                mTrimStart = get()
            }
        }
        trimStart.setRange(0.0f, 0.75f)
        trimStart.duration = 1333L
        trimStart.interpolator = TRIM_START_INTERPOLATOR
        trimStart.repeatCount = Animation.INFINITE

        val trimEnd = object : FloatAnimation() {
            override fun onCalculate(progress: Float) {
                super.onCalculate(progress)
                mTrimEnd = get()
            }
        }
        trimEnd.setRange(0.0f, 0.75f)
        trimEnd.duration = 1333L
        trimEnd.interpolator = TRIM_END_INTERPOLATOR
        trimEnd.repeatCount = Animation.INFINITE

        val trimOffset = object : FloatAnimation() {
            override fun onCalculate(progress: Float) {
                super.onCalculate(progress)
                mTrimOffset = get()
            }
        }
        trimOffset.setRange(0.0f, 0.25f)
        trimOffset.duration = 1333L
        trimOffset.interpolator = LINEAR_INTERPOLATOR
        trimOffset.repeatCount = Animation.INFINITE

        val trimRotation = object : FloatAnimation() {
            override fun onCalculate(progress: Float) {
                super.onCalculate(progress)
                mTrimRotation = get()
            }
        }
        trimRotation.setRange(0.0f, 720.0f)
        trimRotation.duration = 6665L
        trimRotation.interpolator = LINEAR_INTERPOLATOR
        trimRotation.repeatCount = Animation.INFINITE

        mAnimations.add(trimStart)
        mAnimations.add(trimEnd)
        mAnimations.add(trimOffset)
        mAnimations.add(trimRotation)
    }

    private fun startAnimations() {
        val animations = mAnimations
        for (i in 0 until animations.size) {
            animations[i].reset()
        }
    }

    private fun stopAnimations() {
        val animations = mAnimations
        for (i in 0 until animations.size) {
            animations[i].cancel()
        }
    }

    override fun onLayout(
        changeSize: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        super.onLayout(changeSize, left, top, right, bottom)

        val width = right - left
        val height = bottom - top
        mGLPaint.lineWidth = min(width, height) / 12.0f
        mCx = (width / 2).toFloat()
        mCy = (height / 2).toFloat()
        mRadiusX = width / 48.0f * 19.0f
        mRadiusY = height / 48.0f * 19.0f
    }

    fun setColor(color: Int) {
        mGLPaint.color = color
        invalidate()
    }

    fun setBgColor(color: Int) {
        mGLPaint.backgroundColor = color
        invalidate()
    }

    fun setIndeterminate(indeterminate: Boolean) {
        if (mIndeterminate != indeterminate) {
            mIndeterminate = indeterminate
            if (indeterminate) {
                startAnimations()
            } else {
                stopAnimations()
            }
            invalidate()
        }
    }

    fun isIndeterminate(): Boolean = mIndeterminate

    fun setProgress(progress: Float) {
        if (!mIndeterminate) {
            mTrimStart = 0.0f
            mTrimEnd = progress
            mTrimOffset = 0.0f
            mTrimRotation = 0.0f
            invalidate()
        }
    }

    override fun onRender(canvas: GLCanvas) {
        update()

        val width = width
        val height = height
        val cx = width / 2
        val cy = height / 2

        val startAngle = (mTrimStart + mTrimOffset) * 360.0f - 90
        val sweepAngle = max(12.0f, (mTrimEnd - mTrimStart) * 360.0f)
        val rotation = mTrimRotation + startAngle

        canvas.save()

        canvas.translate(cx.toFloat(), cy.toFloat())
        canvas.rotate(rotation, 0f, 0f, 1f)
        if (rotation % 180 != 0f) {
            canvas.translate((-cy).toFloat(), (-cx).toFloat())
        } else {
            canvas.translate((-cx).toFloat(), (-cy).toFloat())
        }

        canvas.drawArc(mCx, mCy, mRadiusX, mRadiusY, sweepAngle, mGLPaint)

        canvas.restore()
    }

    private fun update() {
        var needInvalidate = false

        if (mIndeterminate) {
            val currentTime = AnimationTime.get()
            val animations = mAnimations
            for (i in 0 until animations.size) {
                needInvalidate = needInvalidate or animations[i].calculate(currentTime)
            }
        }

        if (needInvalidate) {
            invalidate()
        }
    }

    companion object {
        private val TRIM_START_INTERPOLATOR: Interpolator
        private val TRIM_END_INTERPOLATOR: Interpolator
        private val LINEAR_INTERPOLATOR: Interpolator = LinearInterpolator()

        init {
            val trimStartPath = Path()
            trimStartPath.moveTo(0.0f, 0.0f)
            trimStartPath.lineTo(0.5f, 0.0f)
            trimStartPath.cubicTo(0.7f, 0.0f, 0.6f, 1f, 1f, 1f)
            TRIM_START_INTERPOLATOR = PathInterpolatorCompat.create(trimStartPath)

            val trimEndPath = Path()
            trimEndPath.moveTo(0.0f, 0.0f)
            trimEndPath.cubicTo(0.2f, 0.0f, 0.1f, 1f, 0.5f, 1f)
            trimEndPath.lineTo(1f, 1f)
            TRIM_END_INTERPOLATOR = PathInterpolatorCompat.create(trimEndPath)
        }
    }
}
