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

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.AbsoluteLayout
import android.widget.PopupWindow
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.drawable.DrawableCompat
import com.lanraragi.reader.R
import com.lanraragi.reader.util.AnimationUtils
import com.lanraragi.reader.util.MathUtils
import com.lanraragi.reader.util.ViewUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Slider : View {

    private lateinit var paint: Paint
    private lateinit var bgPaint: Paint

    private val leftRect = RectF()
    private val rightRect = RectF()

    private lateinit var popup: PopupWindow
    private lateinit var bubble: BubbleView

    private var rangeStart = 0
    private var rangeEnd = 0
    private var progressValue = 0
    private var progressPercent = 0f
    private var drawProgress = 0
    private var drawPercent = 0f
    private var targetProgress = 0

    private var thickness = 0f
    private var radius = 0f

    private var charWidth = 0f
    private var charHeight = 0f

    private var bubbleWidth = 0
    private var bubbleHeight = 0
    private var bubbleMinWidth = 0
    private var bubbleMinHeight = 0

    private var popupX = 0
    private var popupY = 0
    private var popupWidth = 0

    private val locationInWindow = IntArray(2)

    private var bubbleShown = false

    private var drawBubbleScale = 0f

    private var progressAnimation: ValueAnimator? = null
    private lateinit var bubbleScaleAnimation: ValueAnimator

    private var checkForShowBubble: CheckForShowBubble? = null

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    @Suppress("DEPRECATION") // AbsoluteLayout hosts the bubble inside the popup; kept as is
    private fun init(context: Context, attrs: AttributeSet?) {
        paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.textAlign = Paint.Align.CENTER

        val resources = context.resources
        bubbleMinWidth = resources.getDimensionPixelOffset(R.dimen.slider_bubble_width)
        bubbleMinHeight = resources.getDimensionPixelOffset(R.dimen.slider_bubble_height)

        bubble = BubbleView(context, textPaint)
        bubble.scaleX = 0.0f
        bubble.scaleY = 0.0f
        val absoluteLayout = AbsoluteLayout(context)
        absoluteLayout.addView(bubble)
        absoluteLayout.background = null
        popup = PopupWindow(absoluteLayout)
        popup.isOutsideTouchable = false
        popup.isTouchable = false
        popup.isFocusable = false

        val a = context.obtainStyledAttributes(attrs, R.styleable.Slider)
        textPaint.color = a.getColor(R.styleable.Slider_textColor, Color.WHITE)
        textPaint.textSize = a.getDimensionPixelSize(R.styleable.Slider_textSize, 12).toFloat()

        updateTextSize()

        setRange(a.getInteger(R.styleable.Slider_start, 0), a.getInteger(R.styleable.Slider_end, 0))
        progress = a.getInteger(R.styleable.Slider_slider_progress, 0)
        thickness = a.getDimension(R.styleable.Slider_thickness, 2f)
        radius = a.getDimension(R.styleable.Slider_radius, 6f)
        setColor(a.getColor(R.styleable.Slider_color, Color.BLACK))

        bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.color = if (a.getBoolean(R.styleable.Slider_dark, false)) 0x4dffffff else 0x42000000

        a.recycle()

        val progressAnimation = ValueAnimator()
        progressAnimation.interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
        progressAnimation.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            drawPercent = value
            drawProgress = Math.round(MathUtils.lerp(rangeStart.toFloat(), rangeEnd.toFloat(), value))
            updateBubblePosition()
            bubble.setProgress(drawProgress)
            invalidate()
        }
        this.progressAnimation = progressAnimation

        bubbleScaleAnimation = ValueAnimator()
        bubbleScaleAnimation.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            drawBubbleScale = value
            bubble.scaleX = value
            bubble.scaleY = value
            invalidate()
        }
    }

    private fun updateTextSize() {
        val length = CHARACTERS.size
        val widths = FloatArray(length)
        paint.getTextWidths(CHARACTERS, 0, length, widths)
        charWidth = 0.0f
        for (f in widths) {
            charWidth = max(charWidth, f)
        }

        val fm = paint.fontMetrics
        charHeight = fm.bottom - fm.top
    }

    @Suppress("DEPRECATION") // the bubble's AbsoluteLayout.LayoutParams
    private fun updateBubbleSize() {
        val oldWidth = bubbleWidth
        val oldHeight = bubbleHeight
        bubbleWidth = max(
            bubbleMinWidth.toFloat(),
            rangeEnd.toString().length * charWidth + ViewUtils.dp2pix(context, 8f),
        ).toInt()
        bubbleHeight = max(
            bubbleMinHeight.toFloat(),
            charHeight + ViewUtils.dp2pix(context, 8f),
        ).toInt()

        if (oldWidth != bubbleWidth && oldHeight != bubbleHeight) {
            val lp = bubble.layoutParams as AbsoluteLayout.LayoutParams
            lp.width = bubbleWidth
            lp.height = bubbleHeight
            bubble.layoutParams = lp
        }
    }

    private fun updatePopup() {
        val width = width
        val paddingTop = paddingTop
        val paddingBottom = paddingBottom

        getLocationInWindow(locationInWindow)

        popupWidth = (width - radius - radius + bubbleWidth).toInt()
        val popupHeight = bubbleHeight
        popupX = (locationInWindow[0] + radius - bubbleWidth / 2).toInt()
        popupY = (
            locationInWindow[1] - popupHeight + paddingTop + (height - paddingTop - paddingBottom) / 2 -
                radius - ViewUtils.dp2pix(context, 2f)
            ).toInt()

        popup.update(popupX, popupY, popupWidth, popupHeight, false)
    }

    private fun updateBubblePosition() {
        val x = (popupWidth - bubbleWidth) * if (reverse) 1.0f - drawPercent else drawPercent
        bubble.x = x
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        updatePopup()
        updateBubblePosition()
    }

    @SuppressLint("RtlHardcoded") // popupX is an absolute window x from getLocationInWindow, so LEFT is meant
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        popup.showAtLocation(this, Gravity.TOP or Gravity.LEFT, popupX, popupY)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        popup.dismiss()
    }

    private fun startProgressAnimation(percent: Float) {
        val progressAnimation = this.progressAnimation ?: return
        val currentValue: Float
        if (progressAnimation.isRunning) {
            progressAnimation.currentPlayTime = progressAnimation.currentPlayTime
            val value = progressAnimation.animatedValue
            currentValue = if (value is Float) value else drawPercent
        } else {
            currentValue = drawPercent
        }
        progressAnimation.cancel()
        progressAnimation.setFloatValues(currentValue, percent)
        progressAnimation.duration = min(500L, (50 * width * abs(currentValue - percent)).toLong())
        progressAnimation.start()
    }

    private fun startShowBubbleAnimation() {
        bubbleScaleAnimation.cancel()
        bubbleScaleAnimation.setFloatValues(drawBubbleScale, 1.0f)
        bubbleScaleAnimation.interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
        bubbleScaleAnimation.duration = (300 * abs(drawBubbleScale - 1.0f)).toLong()
        bubbleScaleAnimation.start()
    }

    private fun startHideBubbleAnimation() {
        bubbleScaleAnimation.cancel()
        bubbleScaleAnimation.setFloatValues(drawBubbleScale, 0.0f)
        bubbleScaleAnimation.interpolator = AnimationUtils.SLOW_FAST_INTERPOLATOR
        bubbleScaleAnimation.duration = (300 * abs(drawBubbleScale - 0.0f)).toLong()
        bubbleScaleAnimation.start()
    }

    fun setColor(color: Int) {
        paint.color = color
        bubble.setColor(color)
        invalidate()
    }

    fun setRange(start: Int, end: Int) {
        rangeStart = start
        rangeEnd = end
        progress = progressValue

        updateBubbleSize()
    }

    var progress: Int
        get() = progressValue
        set(value) {
            val clamped = MathUtils.clamp(value, rangeStart, rangeEnd)
            val oldProgress = progressValue
            if (progressValue != clamped) {
                progressValue = clamped
                progressPercent = MathUtils.delerp(rangeStart, rangeEnd, progressValue)
                targetProgress = clamped

                if (progressAnimation == null) {
                    // For init
                    drawPercent = progressPercent
                    drawProgress = progressValue
                    updateBubblePosition()
                    bubble.setProgress(drawProgress)
                } else {
                    startProgressAnimation(progressPercent)
                }
                invalidate()
            }
            onSetProgressListener?.onSetProgress(this, clamped, oldProgress, false, true)
        }

    var reverse = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onSetProgressListener: OnSetProgressListener? = null

    override fun onDraw(canvas: Canvas) {
        val width = width
        val height = height
        if (width < ViewUtils.dp2pix(context, 24f)) {
            canvas.drawRect(0f, 0f, width.toFloat(), getHeight().toFloat(), paint)
        } else {
            val paddingLeft = paddingLeft
            val paddingTop = paddingTop
            val paddingRight = paddingRight
            val paddingBottom = paddingBottom
            val halfThickness = thickness / 2

            val saved = canvas.save()

            canvas.translate(0f, (paddingTop + (height - paddingTop - paddingBottom) / 2).toFloat())

            val currentX = paddingLeft + radius + (width - radius - radius - paddingLeft - paddingRight) *
                if (reverse) 1.0f - drawPercent else drawPercent

            leftRect.set(paddingLeft + radius, -halfThickness, currentX, halfThickness)
            rightRect.set(currentX, -halfThickness, width - paddingRight - radius, halfThickness)

            // Draw bar
            if (reverse) {
                canvas.drawRect(rightRect, paint)
                canvas.drawRect(leftRect, bgPaint)
            } else {
                canvas.drawRect(leftRect, paint)
                canvas.drawRect(rightRect, bgPaint)
            }

            // Draw controller
            val scale = 1.0f - drawBubbleScale
            if (scale != 0.0f) {
                canvas.scale(scale, scale, currentX, 0f)
                canvas.drawCircle(currentX, 0f, radius, paint)
            }

            canvas.restoreToCount(saved)
        }
    }

    private fun setShowBubble(showBubble: Boolean) {
        if (bubbleShown != showBubble) {
            bubbleShown = showBubble
            if (showBubble) {
                startShowBubbleAnimation()
            } else {
                startHideBubbleAnimation()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.action
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val listener = onSetProgressListener
                if (listener != null) {
                    if (action == MotionEvent.ACTION_DOWN) {
                        listener.onFingerDown()
                    } else if (action == MotionEvent.ACTION_UP) {
                        listener.onFingerUp()
                    }
                }

                val paddingLeft = paddingLeft
                val paddingRight = paddingRight
                val x = event.x
                var touchProgress = Math.round(
                    MathUtils.lerp(
                        rangeStart.toFloat(), rangeEnd.toFloat(),
                        MathUtils.clamp(
                            (if (reverse) width - paddingLeft - radius - x else x - radius - paddingLeft) /
                                (width - radius - radius - paddingLeft - paddingRight),
                            0.0f, 1.0f,
                        ),
                    ),
                )
                var touchPercent = MathUtils.delerp(rangeStart, rangeEnd, touchProgress)

                // ACTION_CANCEL not changed
                if (action == MotionEvent.ACTION_CANCEL) {
                    touchProgress = progressValue
                    touchPercent = progressPercent
                }

                if (targetProgress != touchProgress) {
                    targetProgress = touchProgress
                    startProgressAnimation(touchPercent)

                    onSetProgressListener?.onSetProgress(this, progressValue, touchProgress, true, false)
                }

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    checkForShowBubble?.let { MAIN_HANDLER.removeCallbacks(it) }
                    setShowBubble(false)
                } else if (action == MotionEvent.ACTION_DOWN) {
                    val checkForShowBubble = this.checkForShowBubble ?: CheckForShowBubble().also { this.checkForShowBubble = it }
                    MAIN_HANDLER.postDelayed(checkForShowBubble, ViewConfiguration.getTapTimeout().toLong())
                }

                if (action == MotionEvent.ACTION_UP) {
                    performClick()
                    val oldProgress = progressValue
                    if (progressValue != touchProgress) {
                        progressValue = touchProgress
                        progressPercent = drawPercent
                    }
                    onSetProgressListener?.onSetProgress(this, touchProgress, oldProgress, true, true)
                }
            }
        }

        return true
    }

    override fun performClick(): Boolean = super.performClick()

    @SuppressLint("ViewConstructor")
    private class BubbleView(context: Context, private val textPaint: Paint) : AppCompatImageView(context) {

        private val bubbleDrawable: Drawable

        private var progressText = ""

        private val textBounds = Rect()

        init {
            setImageResource(R.drawable.v_slider_bubble)
            bubbleDrawable = DrawableCompat.wrap(checkNotNull(drawable))
            setImageDrawable(bubbleDrawable)
        }

        fun setColor(color: Int) {
            DrawableCompat.setTint(bubbleDrawable, color)
        }

        fun setProgress(progress: Int) {
            val str = progress.toString()
            if (str != progressText) {
                progressText = str
                textPaint.getTextBounds(str, 0, str.length, textBounds)
                invalidate()
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = (w / 2).toFloat()
            pivotY = h.toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val width = width
            val height = height
            val x = width / 2
            val y = (height * TEXT_CENTER + textBounds.height() / 2).toInt()
            canvas.drawText(progressText, x.toFloat(), y.toFloat(), textPaint)
        }

        companion object {
            private val TEXT_CENTER = BUBBLE_WIDTH.toFloat() / 2.0f / BUBBLE_HEIGHT
        }
    }

    interface OnSetProgressListener {

        fun onSetProgress(slider: Slider, newProgress: Int, oldProgress: Int, byUser: Boolean, confirm: Boolean)

        fun onFingerDown()

        fun onFingerUp()
    }

    private inner class CheckForShowBubble : Runnable {

        override fun run() {
            setShowBubble(true)
        }
    }

    companion object {
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())

        private val CHARACTERS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        )

        private const val BUBBLE_WIDTH = 26
        private const val BUBBLE_HEIGHT = 32
    }
}
