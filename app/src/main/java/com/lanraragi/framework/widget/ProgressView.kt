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

    private val animators = ArrayList<Animator>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var indeterminate = false

    // It is a trick to avoid first sight stuck. Get it from ProgressBar
    private var shouldStartAnimation = false

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    private fun init(context: Context, attrs: AttributeSet?) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ProgressView)
        val color = a.getColor(R.styleable.ProgressView_color, Color.BLACK)
        paint.color = color
        paint.strokeCap = Paint.Cap.SQUARE
        paint.strokeJoin = Paint.Join.MITER
        paint.style = Paint.Style.STROKE
        indeterminate = a.getBoolean(R.styleable.ProgressView_indeterminate, true)
        setProgress(a.getFloat(R.styleable.ProgressView_progress, 0f))
        a.recycle()

        setupAnimators()
    }

    private fun setupAnimators() {
        val trimStartAnimator = ObjectAnimator.ofFloat(this, TRIM_START, 0.0f, 0.75f)
        trimStartAnimator.duration = 1333L
        trimStartAnimator.interpolator = TRIM_START_INTERPOLATOR
        trimStartAnimator.repeatCount = Animation.INFINITE

        val trimEndAnimator = ObjectAnimator.ofFloat(this, TRIM_END, 0.0f, 0.75f)
        trimEndAnimator.duration = 1333L
        trimEndAnimator.interpolator = TRIM_END_INTERPOLATOR
        trimEndAnimator.repeatCount = Animation.INFINITE

        val trimOffsetAnimator = ObjectAnimator.ofFloat(this, TRIM_OFFSET, 0.0f, 0.25f)
        trimOffsetAnimator.duration = 1333L
        trimOffsetAnimator.interpolator = LINEAR_INTERPOLATOR
        trimOffsetAnimator.repeatCount = Animation.INFINITE

        val trimRotationAnimator = ObjectAnimator.ofFloat(this, TRIM_ROTATION, 0.0f, 720.0f)
        trimRotationAnimator.duration = 6665L
        trimRotationAnimator.interpolator = LINEAR_INTERPOLATOR
        trimRotationAnimator.repeatCount = Animation.INFINITE

        animators.add(trimStartAnimator)
        animators.add(trimEndAnimator)
        animators.add(trimOffsetAnimator)
        animators.add(trimRotationAnimator)
    }

    private fun startAnimation() {
        shouldStartAnimation = true
        postInvalidate()
    }

    private fun startAnimationActually() {
        for (i in 0 until animators.size) {
            val animator = animators[i]
            if (!animator.isRunning) {
                animator.start()
            }
        }
    }

    private fun stopAnimation() {
        shouldStartAnimation = false
        for (i in 0 until animators.size) {
            animators[i].cancel()
        }
    }

    val isRunning: Boolean
        get() {
            for (i in 0 until animators.size) {
                if (animators[i].isRunning) {
                    return true
                }
            }
            return false
        }

    var trimStart = 0.0f
        set(value) {
            field = value
            invalidate()
        }

    var trimEnd = 0.0f
        set(value) {
            field = value
            invalidate()
        }

    var trimOffset = 0.0f
        set(value) {
            field = value
            invalidate()
        }

    var trimRotation = 0.0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (indeterminate && visibility == VISIBLE) {
            startAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        if (indeterminate) {
            stopAnimation()
        }
        // This should come after stopAnimation(), otherwise an invalidate message remains in the
        // queue, which can prevent the entire view hierarchy from being GC'ed during a rotation
        super.onDetachedFromWindow()
    }

    override fun setVisibility(v: Int) {
        if (visibility != v) {
            super.setVisibility(v)

            if (indeterminate) {
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

        if (indeterminate && isAttachedToWindow) {
            if (visibility == GONE || visibility == INVISIBLE) {
                stopAnimation()
            } else if (isAttachedToWindow) {
                startAnimation()
            }
        }
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    var isIndeterminate: Boolean
        get() = indeterminate
        set(value) {
            if (indeterminate != value) {
                indeterminate = value
                if (value) {
                    if (isShown && isAttachedToWindow) {
                        startAnimation()
                    }
                } else {
                    stopAnimation()
                }
            }
        }

    fun setProgress(progress: Float) {
        if (!indeterminate) {
            trimStart = 0f
            trimEnd = progress
            trimOffset = 0f
            trimRotation = 0f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            ViewUtils.getSuitableSize(suggestedMinimumWidth, widthMeasureSpec),
            ViewUtils.getSuitableSize(suggestedMinimumHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.strokeWidth = min(w, h) / 12.0f
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        rect.inset(w / 48.0f * 5.0f, h / 48.0f * 5.0f)
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.rotate(trimRotation, rect.centerX(), rect.centerY())

        val startAngle = (trimStart + trimOffset) * 360.0f - 90
        val sweepAngle = (trimEnd - trimStart) * 360.0f
        canvas.drawArc(rect, startAngle, sweepAngle, false, paint)

        canvas.restoreToCount(saved)

        if (shouldStartAnimation) {
            shouldStartAnimation = false
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
