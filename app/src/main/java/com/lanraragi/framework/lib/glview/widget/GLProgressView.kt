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

    private val paint: GLPaint

    private var centerX = 0f
    private var centerY = 0f
    private var radiusX = 0f
    private var radiusY = 0f

    private var trimStart = 0.0f
    private var trimEnd = 0.0f
    private var trimOffset = 0.0f
    private var trimRotation = 0.0f

    var isIndeterminate = false
        set(indeterminate) {
            if (field != indeterminate) {
                field = indeterminate
                if (indeterminate) {
                    startAnimations()
                } else {
                    stopAnimations()
                }
                invalidate()
            }
        }

    private val animations: MutableList<Animation>

    init {
        paint = GLPaint()
        paint.color = Color.WHITE
        paint.backgroundColor = Color.BLACK
        animations = ArrayList()

        setupAnimations()
    }

    fun setupAnimations() {
        val trimStartAnimation = object : FloatAnimation() {
            override fun onCalculate(progress: Float) {
                super.onCalculate(progress)
                trimStart = get()
            }
        }
        trimStartAnimation.setRange(0.0f, 0.75f)
        trimStartAnimation.duration = 1333L
        trimStartAnimation.interpolator = TRIM_START_INTERPOLATOR
        trimStartAnimation.repeatCount = Animation.INFINITE

        val trimEndAnimation = object : FloatAnimation() {
            override fun onCalculate(progress: Float) {
                super.onCalculate(progress)
                trimEnd = get()
            }
        }
        trimEndAnimation.setRange(0.0f, 0.75f)
        trimEndAnimation.duration = 1333L
        trimEndAnimation.interpolator = TRIM_END_INTERPOLATOR
        trimEndAnimation.repeatCount = Animation.INFINITE

        val trimOffsetAnimation = object : FloatAnimation() {
            override fun onCalculate(progress: Float) {
                super.onCalculate(progress)
                trimOffset = get()
            }
        }
        trimOffsetAnimation.setRange(0.0f, 0.25f)
        trimOffsetAnimation.duration = 1333L
        trimOffsetAnimation.interpolator = LINEAR_INTERPOLATOR
        trimOffsetAnimation.repeatCount = Animation.INFINITE

        val trimRotationAnimation = object : FloatAnimation() {
            override fun onCalculate(progress: Float) {
                super.onCalculate(progress)
                trimRotation = get()
            }
        }
        trimRotationAnimation.setRange(0.0f, 720.0f)
        trimRotationAnimation.duration = 6665L
        trimRotationAnimation.interpolator = LINEAR_INTERPOLATOR
        trimRotationAnimation.repeatCount = Animation.INFINITE

        animations.add(trimStartAnimation)
        animations.add(trimEndAnimation)
        animations.add(trimOffsetAnimation)
        animations.add(trimRotationAnimation)
    }

    private fun startAnimations() {
        val animations = this.animations
        for (i in 0 until animations.size) {
            animations[i].reset()
        }
    }

    private fun stopAnimations() {
        val animations = this.animations
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
        paint.lineWidth = min(width, height) / 12.0f
        centerX = (width / 2).toFloat()
        centerY = (height / 2).toFloat()
        radiusX = width / 48.0f * 19.0f
        radiusY = height / 48.0f * 19.0f
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun setBgColor(color: Int) {
        paint.backgroundColor = color
        invalidate()
    }

    fun setProgress(progress: Float) {
        if (!isIndeterminate) {
            trimStart = 0.0f
            trimEnd = progress
            trimOffset = 0.0f
            trimRotation = 0.0f
            invalidate()
        }
    }

    override fun onRender(canvas: GLCanvas) {
        update()

        val width = this.width
        val height = this.height
        val cx = width / 2
        val cy = height / 2

        val startAngle = (trimStart + trimOffset) * 360.0f - 90
        val sweepAngle = max(12.0f, (trimEnd - trimStart) * 360.0f)
        val rotation = trimRotation + startAngle

        canvas.save()

        canvas.translate(cx.toFloat(), cy.toFloat())
        canvas.rotate(rotation, 0f, 0f, 1f)
        if (rotation % 180 != 0f) {
            canvas.translate((-cy).toFloat(), (-cx).toFloat())
        } else {
            canvas.translate((-cx).toFloat(), (-cy).toFloat())
        }

        canvas.drawArc(centerX, centerY, radiusX, radiusY, sweepAngle, paint)

        canvas.restore()
    }

    private fun update() {
        var needInvalidate = false

        if (isIndeterminate) {
            val currentTime = AnimationTime.get()
            val animations = this.animations
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
