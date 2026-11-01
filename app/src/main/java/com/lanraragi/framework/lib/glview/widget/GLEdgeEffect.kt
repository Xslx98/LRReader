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

import android.graphics.Rect
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Material design edge effect
 */
class GLEdgeEffect(color: Int) {

    private var mGlowAlpha = 0f
    private var mGlowScaleY = 0f

    private var mGlowAlphaStart = 0f
    private var mGlowAlphaFinish = 0f
    private var mGlowScaleYStart = 0f
    private var mGlowScaleYFinish = 0f

    private var mStartTime = 0L
    private var mDuration = 0f

    private val mInterpolator: Interpolator = DecelerateInterpolator()

    private var mState = STATE_IDLE

    private var mPullDistance = 0f

    private val mBounds = Rect()
    private var mColor = color
    private var mRadius = 0f
    private var mBaseGlowScale = 0f
    private var mDisplacement = 0.5f
    private var mTargetDisplacement = 0.5f

    /**
     * Set the size of this edge effect in pixels.
     *
     * @param width Effect width in pixels
     * @param height Effect height in pixels
     */
    fun setSize(width: Int, height: Int) {
        val r = width * 0.75f / SIN
        val y = COS * r
        val h = r - y
        val oRadius = height * 0.75f / SIN
        val oy = COS * oRadius
        val oh = oRadius - oy

        mRadius = r
        mBaseGlowScale = if (h > 0) min(oh / h, 1f) else 1f

        mBounds.set(mBounds.left, mBounds.top, width, min(height.toFloat(), h).toInt())
    }

    /**
     * Reports if this EdgeEffect's animation is finished. If this method returns false
     * after a call to [draw] the host widget should schedule another
     * drawing pass to continue the animation.
     *
     * @return true if animation is finished, false if drawing should continue on the next frame.
     */
    fun isFinished(): Boolean = mState == STATE_IDLE

    /**
     * Immediately finish the current animation.
     * After this call [isFinished] will return true.
     */
    fun finish() {
        mState = STATE_IDLE
    }

    /**
     * A view should call this when content is pulled away from an edge by the user.
     * This will update the state of the current visual effect and its associated animation.
     * The host view should always [android.view.View.invalidate] after this
     * and draw the results accordingly.
     *
     * Views using EdgeEffect should favor [onPull] with a displacement when the
     * displacement of the pull point is known.
     *
     * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
     * 1.f (full length of the view) or negative values to express change
     * back toward the edge reached to initiate the effect.
     */
    fun onPull(deltaDistance: Float) {
        onPull(deltaDistance, 0.5f)
    }

    /**
     * A view should call this when content is pulled away from an edge by the user.
     * This will update the state of the current visual effect and its associated animation.
     * The host view should always [android.view.View.invalidate] after this
     * and draw the results accordingly.
     *
     * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
     * 1.f (full length of the view) or negative values to express change
     * back toward the edge reached to initiate the effect.
     * @param displacement The displacement from the starting side of the effect of the point
     * initiating the pull. In the case of touch this is the finger position.
     * Values may be from 0-1.
     */
    fun onPull(deltaDistance: Float, displacement: Float) {
        val now = AnimationUtils.currentAnimationTimeMillis()
        mTargetDisplacement = displacement
        if (mState == STATE_PULL_DECAY && (now - mStartTime).toFloat() < mDuration) {
            return
        }
        if (mState != STATE_PULL) {
            mGlowScaleY = max(PULL_GLOW_BEGIN, mGlowScaleY)
        }
        mState = STATE_PULL

        mStartTime = now
        mDuration = PULL_TIME.toFloat()

        mPullDistance += deltaDistance

        val absdd = abs(deltaDistance)
        val glowAlpha = min(MAX_ALPHA, mGlowAlpha + (absdd * PULL_DISTANCE_ALPHA_GLOW_FACTOR))
        mGlowAlphaStart = glowAlpha
        mGlowAlpha = glowAlpha

        if (mPullDistance == 0f) {
            mGlowScaleYStart = 0f
            mGlowScaleY = 0f
        } else {
            val scale = max(
                0f,
                1 - 1 /
                    sqrt((abs(mPullDistance) * mBounds.height()).toDouble()).toFloat() - 0.3f
            ) / 0.7f

            mGlowScaleYStart = scale
            mGlowScaleY = scale
        }

        mGlowAlphaFinish = mGlowAlpha
        mGlowScaleYFinish = mGlowScaleY
    }

    /**
     * Call when the object is released after being pulled.
     * This will begin the "decay" phase of the effect. After calling this method
     * the host view should [android.view.View.invalidate] and thereby
     * draw the results accordingly.
     */
    fun onRelease() {
        mPullDistance = 0f

        if (mState != STATE_PULL && mState != STATE_PULL_DECAY) {
            return
        }

        mState = STATE_RECEDE
        mGlowAlphaStart = mGlowAlpha
        mGlowScaleYStart = mGlowScaleY

        mGlowAlphaFinish = 0f
        mGlowScaleYFinish = 0f

        mStartTime = AnimationUtils.currentAnimationTimeMillis()
        mDuration = RECEDE_TIME.toFloat()
    }

    /**
     * Call when the effect absorbs an impact at the given velocity.
     * Used when a fling reaches the scroll boundary.
     *
     * When using a [android.widget.Scroller] or [android.widget.OverScroller],
     * the method `getCurrVelocity` will provide a reasonable approximation
     * to use here.
     *
     * @param velocity Velocity at impact in pixels per second.
     */
    fun onAbsorb(velocity: Int) {
        mState = STATE_ABSORB
        val clampedVelocity = min(max(MIN_VELOCITY, abs(velocity)), MAX_VELOCITY)

        mStartTime = AnimationUtils.currentAnimationTimeMillis()
        mDuration = 0.15f + (clampedVelocity * 0.02f)

        // The glow depends more on the velocity, and therefore starts out
        // nearly invisible.
        mGlowAlphaStart = 0.3f
        mGlowScaleYStart = max(mGlowScaleY, 0f)

        // Growth for the size of the glow should be quadratic to properly
        // respond
        // to a user's scrolling speed. The faster the scrolling speed, the more
        // intense the effect should be for both the size and the saturation.
        mGlowScaleYFinish = min(0.025f + (clampedVelocity * (clampedVelocity / 100) * 0.00015f) / 2, 1f)
        // Alpha should change for the glow as well as size.
        mGlowAlphaFinish = max(
            mGlowAlphaStart,
            min(clampedVelocity * VELOCITY_GLOW_FACTOR * .00001f, MAX_ALPHA)
        )
        mTargetDisplacement = 0.5f
    }

    /**
     * Set the color of this edge effect in argb.
     *
     * @param color Color in argb
     */
    fun setColor(color: Int) {
        mColor = color
    }

    /**
     * Return the color of this edge effect in argb.
     * @return The color of this edge effect in argb
     */
    fun getColor(): Int = mColor

    /**
     * Draw into the provided canvas. Assumes that the canvas has been rotated
     * accordingly and the size has been set. The effect will be drawn the full
     * width of X=0 to X=width, beginning from Y=0 and extending to some factor <
     * 1.f of height.
     *
     * @param canvas Canvas to draw into
     * @return true if drawing should continue beyond this frame to continue the
     * animation
     */
    fun draw(canvas: GLCanvas): Boolean {
        update()

        canvas.save()

        val centerX = mBounds.centerX().toFloat()
        val centerY = mBounds.height() - mRadius

        canvas.translate(-centerX, 0f)
        canvas.scale(1f, min(mGlowScaleY, 1f) * mBaseGlowScale, 0f)
        canvas.translate(centerX, 0f)

        val displacement = max(0f, min(mDisplacement, 1f)) - 0.5f
        val translateX = mBounds.width() * displacement / 2

        canvas.translate(translateX, 0f)
        canvas.fillOval(centerX, centerY, mRadius, mRadius, mColor)
        canvas.restore()

        var oneLastFrame = false
        if (mState == STATE_RECEDE && mGlowScaleY == 0f) {
            mState = STATE_IDLE
            oneLastFrame = true
        }

        return mState != STATE_IDLE || oneLastFrame
    }

    /**
     * Return the maximum height that the edge effect will be drawn at given the original
     * [input size][setSize].
     * @return The maximum height of the edge effect
     */
    fun getMaxHeight(): Int = (mBounds.height() * MAX_GLOW_SCALE + 0.5f).toInt()

    private fun update() {
        val time = AnimationUtils.currentAnimationTimeMillis()
        val t = min((time - mStartTime).toFloat() / mDuration, 1f)

        val interp = mInterpolator.getInterpolation(t)

        mGlowAlpha = mGlowAlphaStart + (mGlowAlphaFinish - mGlowAlphaStart) * interp
        mGlowScaleY = mGlowScaleYStart + (mGlowScaleYFinish - mGlowScaleYStart) * interp
        mDisplacement = (mDisplacement + mTargetDisplacement) / 2

        if (t >= 1f - EPSILON) {
            when (mState) {
                STATE_ABSORB -> {
                    mState = STATE_RECEDE
                    mStartTime = AnimationUtils.currentAnimationTimeMillis()
                    mDuration = RECEDE_TIME.toFloat()

                    mGlowAlphaStart = mGlowAlpha
                    mGlowScaleYStart = mGlowScaleY

                    // After absorb, the glow should fade to nothing.
                    mGlowAlphaFinish = 0f
                    mGlowScaleYFinish = 0f
                }

                STATE_PULL -> {
                    mState = STATE_PULL_DECAY
                    mStartTime = AnimationUtils.currentAnimationTimeMillis()
                    mDuration = PULL_DECAY_TIME.toFloat()

                    mGlowAlphaStart = mGlowAlpha
                    mGlowScaleYStart = mGlowScaleY

                    // After pull, the glow should fade to nothing.
                    mGlowAlphaFinish = 0f
                    mGlowScaleYFinish = 0f
                }

                STATE_PULL_DECAY -> mState = STATE_RECEDE
                STATE_RECEDE -> mState = STATE_IDLE
            }
        }
    }

    companion object {
        // Time it will take the effect to fully recede in ms
        private const val RECEDE_TIME = 600

        // Time it will take before a pulled glow begins receding in ms
        private const val PULL_TIME = 167

        // Time it will take in ms for a pulled glow to decay to partial strength before release
        private const val PULL_DECAY_TIME = 2000

        private const val MAX_ALPHA = 0.5f

        private const val MAX_GLOW_SCALE = 2f

        private const val PULL_GLOW_BEGIN = 0f

        // Minimum velocity that will be absorbed
        private const val MIN_VELOCITY = 100

        // Maximum velocity, clamps at this value
        private const val MAX_VELOCITY = 10000

        private const val EPSILON = 0.001f

        private const val ANGLE = Math.PI / 6
        private val SIN = sin(ANGLE).toFloat()
        private val COS = cos(ANGLE).toFloat()

        private const val STATE_IDLE = 0
        private const val STATE_PULL = 1
        private const val STATE_ABSORB = 2
        private const val STATE_RECEDE = 3
        private const val STATE_PULL_DECAY = 4

        private const val PULL_DISTANCE_ALPHA_GLOW_FACTOR = 0.8f

        private const val VELOCITY_GLOW_FACTOR = 6
    }
}
