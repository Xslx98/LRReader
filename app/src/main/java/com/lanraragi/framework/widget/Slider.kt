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

    private lateinit var mContext: Context

    private lateinit var mPaint: Paint
    private lateinit var mBgPaint: Paint

    private val mLeftRectF = RectF()
    private val mRightRectF = RectF()

    private lateinit var mPopup: PopupWindow
    private lateinit var mBubble: BubbleView

    private var mStart = 0
    private var mEnd = 0
    private var mProgress = 0
    private var mPercent = 0f
    private var mDrawProgress = 0
    private var mDrawPercent = 0f
    private var mTargetProgress = 0

    private var mThickness = 0f
    private var mRadius = 0f

    private var mCharWidth = 0f
    private var mCharHeight = 0f

    private var mBubbleWidth = 0
    private var mBubbleHeight = 0
    private var mBubbleMinWidth = 0
    private var mBubbleMinHeight = 0

    private var mPopupX = 0
    private var mPopupY = 0
    private var mPopupWidth = 0

    private val mTemp = IntArray(2)

    private var mReverse = false

    private var mShowBubble = false

    private var mDrawBubbleScale = 0f

    private var mProgressAnimation: ValueAnimator? = null
    private lateinit var mBubbleScaleAnimation: ValueAnimator

    private var mListener: OnSetProgressListener? = null

    private var mCheckForShowBubble: CheckForShowBubble? = null

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    @Suppress("DEPRECATION") // AbsoluteLayout hosts the bubble inside the popup; kept as is
    private fun init(context: Context, attrs: AttributeSet?) {
        mContext = context
        mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.textAlign = Paint.Align.CENTER

        val resources = context.resources
        mBubbleMinWidth = resources.getDimensionPixelOffset(R.dimen.slider_bubble_width)
        mBubbleMinHeight = resources.getDimensionPixelOffset(R.dimen.slider_bubble_height)

        mBubble = BubbleView(context, textPaint)
        mBubble.scaleX = 0.0f
        mBubble.scaleY = 0.0f
        val absoluteLayout = AbsoluteLayout(context)
        absoluteLayout.addView(mBubble)
        absoluteLayout.background = null
        mPopup = PopupWindow(absoluteLayout)
        mPopup.isOutsideTouchable = false
        mPopup.isTouchable = false
        mPopup.isFocusable = false

        val a = context.obtainStyledAttributes(attrs, R.styleable.Slider)
        textPaint.color = a.getColor(R.styleable.Slider_textColor, Color.WHITE)
        textPaint.textSize = a.getDimensionPixelSize(R.styleable.Slider_textSize, 12).toFloat()

        updateTextSize()

        setRange(a.getInteger(R.styleable.Slider_start, 0), a.getInteger(R.styleable.Slider_end, 0))
        setProgress(a.getInteger(R.styleable.Slider_slider_progress, 0))
        mThickness = a.getDimension(R.styleable.Slider_thickness, 2f)
        mRadius = a.getDimension(R.styleable.Slider_radius, 6f)
        setColor(a.getColor(R.styleable.Slider_color, Color.BLACK))

        mBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mBgPaint.color = if (a.getBoolean(R.styleable.Slider_dark, false)) 0x4dffffff else 0x42000000

        a.recycle()

        val progressAnimation = ValueAnimator()
        progressAnimation.interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
        progressAnimation.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            mDrawPercent = value
            mDrawProgress = Math.round(MathUtils.lerp(mStart.toFloat(), mEnd.toFloat(), value))
            updateBubblePosition()
            mBubble.setProgress(mDrawProgress)
            invalidate()
        }
        mProgressAnimation = progressAnimation

        mBubbleScaleAnimation = ValueAnimator()
        mBubbleScaleAnimation.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            mDrawBubbleScale = value
            mBubble.scaleX = value
            mBubble.scaleY = value
            invalidate()
        }
    }

    private fun updateTextSize() {
        val length = CHARACTERS.size
        val widths = FloatArray(length)
        mPaint.getTextWidths(CHARACTERS, 0, length, widths)
        mCharWidth = 0.0f
        for (f in widths) {
            mCharWidth = max(mCharWidth, f)
        }

        val fm = mPaint.fontMetrics
        mCharHeight = fm.bottom - fm.top
    }

    @Suppress("DEPRECATION") // the bubble's AbsoluteLayout.LayoutParams
    private fun updateBubbleSize() {
        val oldWidth = mBubbleWidth
        val oldHeight = mBubbleHeight
        mBubbleWidth = max(
            mBubbleMinWidth.toFloat(),
            mEnd.toString().length * mCharWidth + ViewUtils.dp2pix(mContext, 8f),
        ).toInt()
        mBubbleHeight = max(
            mBubbleMinHeight.toFloat(),
            mCharHeight + ViewUtils.dp2pix(mContext, 8f),
        ).toInt()

        if (oldWidth != mBubbleWidth && oldHeight != mBubbleHeight) {
            val lp = mBubble.layoutParams as AbsoluteLayout.LayoutParams
            lp.width = mBubbleWidth
            lp.height = mBubbleHeight
            mBubble.layoutParams = lp
        }
    }

    private fun updatePopup() {
        val width = width
        val paddingTop = paddingTop
        val paddingBottom = paddingBottom

        getLocationInWindow(mTemp)

        mPopupWidth = (width - mRadius - mRadius + mBubbleWidth).toInt()
        val popupHeight = mBubbleHeight
        mPopupX = (mTemp[0] + mRadius - mBubbleWidth / 2).toInt()
        mPopupY = (
            mTemp[1] - popupHeight + paddingTop + (height - paddingTop - paddingBottom) / 2 -
                mRadius - ViewUtils.dp2pix(mContext, 2f)
            ).toInt()

        mPopup.update(mPopupX, mPopupY, mPopupWidth, popupHeight, false)
    }

    private fun updateBubblePosition() {
        val x = (mPopupWidth - mBubbleWidth) * if (mReverse) 1.0f - mDrawPercent else mDrawPercent
        mBubble.x = x
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        updatePopup()
        updateBubblePosition()
    }

    @SuppressLint("RtlHardcoded") // mPopupX is an absolute window x from getLocationInWindow, so LEFT is meant
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        mPopup.showAtLocation(this, Gravity.TOP or Gravity.LEFT, mPopupX, mPopupY)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        mPopup.dismiss()
    }

    private fun startProgressAnimation(percent: Float) {
        val progressAnimation = mProgressAnimation ?: return
        val currentValue: Float
        if (progressAnimation.isRunning) {
            progressAnimation.currentPlayTime = progressAnimation.currentPlayTime
            val value = progressAnimation.animatedValue
            currentValue = if (value is Float) value else mDrawPercent
        } else {
            currentValue = mDrawPercent
        }
        progressAnimation.cancel()
        progressAnimation.setFloatValues(currentValue, percent)
        progressAnimation.duration = min(500L, (50 * width * abs(currentValue - percent)).toLong())
        progressAnimation.start()
    }

    private fun startShowBubbleAnimation() {
        mBubbleScaleAnimation.cancel()
        mBubbleScaleAnimation.setFloatValues(mDrawBubbleScale, 1.0f)
        mBubbleScaleAnimation.interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
        mBubbleScaleAnimation.duration = (300 * abs(mDrawBubbleScale - 1.0f)).toLong()
        mBubbleScaleAnimation.start()
    }

    private fun startHideBubbleAnimation() {
        mBubbleScaleAnimation.cancel()
        mBubbleScaleAnimation.setFloatValues(mDrawBubbleScale, 0.0f)
        mBubbleScaleAnimation.interpolator = AnimationUtils.SLOW_FAST_INTERPOLATOR
        mBubbleScaleAnimation.duration = (300 * abs(mDrawBubbleScale - 0.0f)).toLong()
        mBubbleScaleAnimation.start()
    }

    fun setColor(color: Int) {
        mPaint.color = color
        mBubble.setColor(color)
        invalidate()
    }

    fun setRange(start: Int, end: Int) {
        mStart = start
        mEnd = end
        setProgress(mProgress)

        updateBubbleSize()
    }

    fun setProgress(progress: Int) {
        val clamped = MathUtils.clamp(progress, mStart, mEnd)
        val oldProgress = mProgress
        if (mProgress != clamped) {
            mProgress = clamped
            mPercent = MathUtils.delerp(mStart, mEnd, mProgress)
            mTargetProgress = clamped

            if (mProgressAnimation == null) {
                // For init
                mDrawPercent = mPercent
                mDrawProgress = mProgress
                updateBubblePosition()
                mBubble.setProgress(mDrawProgress)
            } else {
                startProgressAnimation(mPercent)
            }
            invalidate()
        }
        mListener?.onSetProgress(this, clamped, oldProgress, false, true)
    }

    fun getProgress(): Int = mProgress

    fun setReverse(reverse: Boolean) {
        if (mReverse != reverse) {
            mReverse = reverse
            invalidate()
        }
    }

    fun setOnSetProgressListener(listener: OnSetProgressListener?) {
        mListener = listener
    }

    override fun onDraw(canvas: Canvas) {
        val width = width
        val height = height
        if (width < ViewUtils.dp2pix(mContext, 24f)) {
            canvas.drawRect(0f, 0f, width.toFloat(), getHeight().toFloat(), mPaint)
        } else {
            val paddingLeft = paddingLeft
            val paddingTop = paddingTop
            val paddingRight = paddingRight
            val paddingBottom = paddingBottom
            val thickness = mThickness
            val radius = mRadius
            val halfThickness = thickness / 2

            val saved = canvas.save()

            canvas.translate(0f, (paddingTop + (height - paddingTop - paddingBottom) / 2).toFloat())

            val currentX = paddingLeft + radius + (width - radius - radius - paddingLeft - paddingRight) *
                if (mReverse) 1.0f - mDrawPercent else mDrawPercent

            mLeftRectF.set(paddingLeft + radius, -halfThickness, currentX, halfThickness)
            mRightRectF.set(currentX, -halfThickness, width - paddingRight - radius, halfThickness)

            // Draw bar
            if (mReverse) {
                canvas.drawRect(mRightRectF, mPaint)
                canvas.drawRect(mLeftRectF, mBgPaint)
            } else {
                canvas.drawRect(mLeftRectF, mPaint)
                canvas.drawRect(mRightRectF, mBgPaint)
            }

            // Draw controller
            val scale = 1.0f - mDrawBubbleScale
            if (scale != 0.0f) {
                canvas.scale(scale, scale, currentX, 0f)
                canvas.drawCircle(currentX, 0f, radius, mPaint)
            }

            canvas.restoreToCount(saved)
        }
    }

    private fun setShowBubble(showBubble: Boolean) {
        if (mShowBubble != showBubble) {
            mShowBubble = showBubble
            if (showBubble) {
                startShowBubbleAnimation()
            } else {
                startHideBubbleAnimation()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility") // TODO(2.4-idiom)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.action
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val listener = mListener
                if (listener != null) {
                    if (action == MotionEvent.ACTION_DOWN) {
                        listener.onFingerDown()
                    } else if (action == MotionEvent.ACTION_UP) {
                        listener.onFingerUp()
                    }
                }

                val paddingLeft = paddingLeft
                val paddingRight = paddingRight
                val radius = mRadius
                val x = event.x
                var progress = Math.round(
                    MathUtils.lerp(
                        mStart.toFloat(), mEnd.toFloat(),
                        MathUtils.clamp(
                            (if (mReverse) width - paddingLeft - radius - x else x - radius - paddingLeft) /
                                (width - radius - radius - paddingLeft - paddingRight),
                            0.0f, 1.0f,
                        ),
                    ),
                )
                var percent = MathUtils.delerp(mStart, mEnd, progress)

                // ACTION_CANCEL not changed
                if (action == MotionEvent.ACTION_CANCEL) {
                    progress = mProgress
                    percent = mPercent
                }

                if (mTargetProgress != progress) {
                    mTargetProgress = progress
                    startProgressAnimation(percent)

                    mListener?.onSetProgress(this, mProgress, progress, true, false)
                }

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mCheckForShowBubble?.let { MAIN_HANDLER.removeCallbacks(it) }
                    setShowBubble(false)
                } else if (action == MotionEvent.ACTION_DOWN) {
                    val checkForShowBubble = mCheckForShowBubble ?: CheckForShowBubble().also { mCheckForShowBubble = it }
                    MAIN_HANDLER.postDelayed(checkForShowBubble, ViewConfiguration.getTapTimeout().toLong())
                }

                if (action == MotionEvent.ACTION_UP) {
                    val oldProgress = mProgress
                    if (mProgress != progress) {
                        mProgress = progress
                        mPercent = mDrawPercent
                    }
                    mListener?.onSetProgress(this, progress, oldProgress, true, true)
                }
            }
        }

        return true
    }

    @SuppressLint("ViewConstructor")
    private class BubbleView(context: Context, paint: Paint) : AppCompatImageView(context) {

        private val mDrawable: Drawable

        private val mTextPaint: Paint

        private var mProgressStr = ""

        private val mRect = Rect()

        init {
            setImageResource(R.drawable.v_slider_bubble)
            mDrawable = DrawableCompat.wrap(checkNotNull(drawable))
            setImageDrawable(mDrawable)
            mTextPaint = paint
        }

        fun setColor(color: Int) {
            DrawableCompat.setTint(mDrawable, color)
        }

        fun setProgress(progress: Int) {
            val str = progress.toString()
            if (str != mProgressStr) {
                mProgressStr = str
                mTextPaint.getTextBounds(str, 0, str.length, mRect)
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
            val y = (height * TEXT_CENTER + mRect.height() / 2).toInt()
            canvas.drawText(mProgressStr, x.toFloat(), y.toFloat(), mTextPaint)
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
