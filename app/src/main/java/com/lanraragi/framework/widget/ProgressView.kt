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

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.FloatProperty
import android.view.View
import android.view.animation.Animation
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import androidx.core.view.animation.PathInterpolatorCompat
import com.lanraragi.reader.R
import com.lanraragi.reader.util.ViewUtils
import kotlin.math.min

// Base on android.graphics.drawable.MaterialProgressDrawable in L preview
class ProgressView : View {

    private val mAnimators = ArrayList<Animator>()
    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mRectF = RectF()

    private var mIndeterminate = false

    private var mTrimStart = 0.0f
    private var mTrimEnd = 0.0f
    private var mTrimOffset = 0.0f
    private var mTrimRotation = 0.0f

    // It is a trick to avoid first sight stuck. Get it from ProgressBar
    private var mShouldStartAnimationDrawable = false

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    private fun init(context: Context, attrs: AttributeSet?) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ProgressView)
        val color = a.getColor(R.styleable.ProgressView_color, Color.BLACK)
        mPaint.color = color
        mPaint.strokeCap = Paint.Cap.SQUARE
        mPaint.strokeJoin = Paint.Join.MITER
        mPaint.style = Paint.Style.STROKE
        mIndeterminate = a.getBoolean(R.styleable.ProgressView_indeterminate, true)
        setProgress(a.getFloat(R.styleable.ProgressView_progress, 0f))
        a.recycle()

        setupAnimators()
    }

    private fun setupAnimators() {
        val trimStart = ObjectAnimator.ofFloat(this, TRIM_START, 0.0f, 0.75f)
        trimStart.duration = 1333L
        trimStart.interpolator = TRIM_START_INTERPOLATOR
        trimStart.repeatCount = Animation.INFINITE

        val trimEnd = ObjectAnimator.ofFloat(this, TRIM_END, 0.0f, 0.75f)
        trimEnd.duration = 1333L
        trimEnd.interpolator = TRIM_END_INTERPOLATOR
        trimEnd.repeatCount = Animation.INFINITE

        val trimOffset = ObjectAnimator.ofFloat(this, TRIM_OFFSET, 0.0f, 0.25f)
        trimOffset.duration = 1333L
        trimOffset.interpolator = LINEAR_INTERPOLATOR
        trimOffset.repeatCount = Animation.INFINITE

        val trimRotation = ObjectAnimator.ofFloat(this, TRIM_ROTATION, 0.0f, 720.0f)
        trimRotation.duration = 6665L
        trimRotation.interpolator = LINEAR_INTERPOLATOR
        trimRotation.repeatCount = Animation.INFINITE

        mAnimators.add(trimStart)
        mAnimators.add(trimEnd)
        mAnimators.add(trimOffset)
        mAnimators.add(trimRotation)
    }

    private fun startAnimation() {
        mShouldStartAnimationDrawable = true
        postInvalidate()
    }

    private fun startAnimationActually() {
        val animators = mAnimators
        for (i in 0 until animators.size) {
            val animator = animators[i]
            if (!animator.isRunning) {
                animator.start()
            }
        }
    }

    private fun stopAnimation() {
        mShouldStartAnimationDrawable = false
        val animators = mAnimators
        for (i in 0 until animators.size) {
            animators[i].cancel()
        }
    }

    val isRunning: Boolean
        get() {
            val animators = mAnimators
            for (i in 0 until animators.size) {
                if (animators[i].isRunning) {
                    return true
                }
            }
            return false
        }

    var trimStart: Float
        get() = mTrimStart
        set(trimStart) {
            mTrimStart = trimStart
            invalidate()
        }

    var trimEnd: Float
        get() = mTrimEnd
        set(trimEnd) {
            mTrimEnd = trimEnd
            invalidate()
        }

    var trimOffset: Float
        get() = mTrimOffset
        set(trimOffset) {
            mTrimOffset = trimOffset
            invalidate()
        }

    var trimRotation: Float
        get() = mTrimRotation
        set(trimRotation) {
            mTrimRotation = trimRotation
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mIndeterminate && visibility == VISIBLE) {
            startAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        if (mIndeterminate) {
            stopAnimation()
        }
        // This should come after stopAnimation(), otherwise an invalidate message remains in the
        // queue, which can prevent the entire view hierarchy from being GC'ed during a rotation
        super.onDetachedFromWindow()
    }

    override fun setVisibility(v: Int) {
        if (visibility != v) {
            super.setVisibility(v)

            if (mIndeterminate) {
                if (v == GONE || v == INVISIBLE) {
                    stopAnimation()
                } else if (isAttachedToWindow) {
                    startAnimation()
                }
            }
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)

        if (mIndeterminate && isAttachedToWindow) {
            if (visibility == GONE || visibility == INVISIBLE) {
                stopAnimation()
            } else if (isAttachedToWindow) {
                startAnimation()
            }
        }
    }

    fun setColor(color: Int) {
        mPaint.color = color
        invalidate()
    }

    var isIndeterminate: Boolean
        get() = mIndeterminate
        set(indeterminate) {
            if (mIndeterminate != indeterminate) {
                mIndeterminate = indeterminate
                if (indeterminate) {
                    if (isShown && isAttachedToWindow) {
                        startAnimation()
                    }
                } else {
                    stopAnimation()
                }
            }
        }

    fun setProgress(progress: Float) {
        if (!mIndeterminate) {
            mTrimStart = 0f
            mTrimEnd = progress
            mTrimOffset = 0f
            mTrimRotation = 0f
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            ViewUtils.getSuitableSize(suggestedMinimumWidth, widthMeasureSpec),
            ViewUtils.getSuitableSize(suggestedMinimumHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        mPaint.strokeWidth = min(w, h) / 12.0f
        mRectF.set(0f, 0f, w.toFloat(), h.toFloat())
        mRectF.inset(w / 48.0f * 5.0f, h / 48.0f * 5.0f)
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.rotate(mTrimRotation, mRectF.centerX(), mRectF.centerY())

        val startAngle = (mTrimStart + mTrimOffset) * 360.0f - 90
        val sweepAngle = (mTrimEnd - mTrimStart) * 360.0f
        canvas.drawArc(mRectF, startAngle, sweepAngle, false, mPaint)

        canvas.restoreToCount(saved)

        if (mShouldStartAnimationDrawable) {
            mShouldStartAnimationDrawable = false
            startAnimationActually()
        }
    }

    companion object {
        // Typed animator properties: string-named ones made ObjectAnimator find the
        // setters by reflection, which only worked because R8 happens to keep View
        // setters (audit 2026-10-06e STAB-02).
        private val TRIM_START: FloatProperty<ProgressView> = object : FloatProperty<ProgressView>("trimStart") {
            override fun setValue(view: ProgressView, value: Float) {
                view.trimStart = value
            }

            override fun get(view: ProgressView): Float = view.trimStart
        }
        private val TRIM_END: FloatProperty<ProgressView> = object : FloatProperty<ProgressView>("trimEnd") {
            override fun setValue(view: ProgressView, value: Float) {
                view.trimEnd = value
            }

            override fun get(view: ProgressView): Float = view.trimEnd
        }
        private val TRIM_OFFSET: FloatProperty<ProgressView> = object : FloatProperty<ProgressView>("trimOffset") {
            override fun setValue(view: ProgressView, value: Float) {
                view.trimOffset = value
            }

            override fun get(view: ProgressView): Float = view.trimOffset
        }
        private val TRIM_ROTATION: FloatProperty<ProgressView> = object : FloatProperty<ProgressView>("trimRotation") {
            override fun setValue(view: ProgressView, value: Float) {
                view.trimRotation = value
            }

            override fun get(view: ProgressView): Float = view.trimRotation
        }

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
