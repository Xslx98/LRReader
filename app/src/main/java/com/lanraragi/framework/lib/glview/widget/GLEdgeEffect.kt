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

    private var glowAlpha = 0f
    private var glowScaleY = 0f

    private var glowAlphaStart = 0f
    private var glowAlphaFinish = 0f
    private var glowScaleYStart = 0f
    private var glowScaleYFinish = 0f

    private var startTime = 0L
    private var duration = 0f

    private val interpolator: Interpolator = DecelerateInterpolator()

    private var state = STATE_IDLE

    private var pullDistance = 0f

    private val bounds = Rect()

    // The color of this edge effect in argb.
    private val color = color
    private var radius = 0f
    private var baseGlowScale = 0f
    private var displacement = 0.5f
    private var targetDisplacement = 0.5f

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

        radius = r
        baseGlowScale = if (h > 0) min(oh / h, 1f) else 1f

        bounds.set(bounds.left, bounds.top, width, min(height.toFloat(), h).toInt())
    }

    /**
     * Reports if this EdgeEffect's animation is finished. If this method returns false
     * after a call to [draw] the host widget should schedule another
     * drawing pass to continue the animation.
     *
     * @return true if animation is finished, false if drawing should continue on the next frame.
     */
    val isFinished: Boolean
        get() = state == STATE_IDLE

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
        targetDisplacement = displacement
        if (state == STATE_PULL_DECAY && (now - startTime).toFloat() < duration) {
            return
        }
        if (state != STATE_PULL) {
            glowScaleY = max(PULL_GLOW_BEGIN, glowScaleY)
        }
        state = STATE_PULL

        startTime = now
        duration = PULL_TIME.toFloat()

        pullDistance += deltaDistance

        val absdd = abs(deltaDistance)
        val newGlowAlpha = min(MAX_ALPHA, glowAlpha + (absdd * PULL_DISTANCE_ALPHA_GLOW_FACTOR))
        glowAlphaStart = newGlowAlpha
        glowAlpha = newGlowAlpha

        if (pullDistance == 0f) {
            glowScaleYStart = 0f
            glowScaleY = 0f
        } else {
            val scale = max(
                0f,
                1 - 1 /
                    sqrt((abs(pullDistance) * bounds.height()).toDouble()).toFloat() - 0.3f
            ) / 0.7f

            glowScaleYStart = scale
            glowScaleY = scale
        }

        glowAlphaFinish = glowAlpha
        glowScaleYFinish = glowScaleY
    }

    /**
     * Call when the object is released after being pulled.
     * This will begin the "decay" phase of the effect. After calling this method
     * the host view should [android.view.View.invalidate] and thereby
     * draw the results accordingly.
     */
    fun onRelease() {
        pullDistance = 0f

        if (state != STATE_PULL && state != STATE_PULL_DECAY) {
            return
        }

        state = STATE_RECEDE
        glowAlphaStart = glowAlpha
        glowScaleYStart = glowScaleY

        glowAlphaFinish = 0f
        glowScaleYFinish = 0f

        startTime = AnimationUtils.currentAnimationTimeMillis()
        duration = RECEDE_TIME.toFloat()
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
        state = STATE_ABSORB
        val clampedVelocity = min(max(MIN_VELOCITY, abs(velocity)), MAX_VELOCITY)

        startTime = AnimationUtils.currentAnimationTimeMillis()
        duration = 0.15f + (clampedVelocity * 0.02f)

        // The glow depends more on the velocity, and therefore starts out
        // nearly invisible.
        glowAlphaStart = 0.3f
        glowScaleYStart = max(glowScaleY, 0f)

        // Growth for the size of the glow should be quadratic to properly
        // respond
        // to a user's scrolling speed. The faster the scrolling speed, the more
        // intense the effect should be for both the size and the saturation.
        glowScaleYFinish = min(0.025f + (clampedVelocity * (clampedVelocity / 100) * 0.00015f) / 2, 1f)
        // Alpha should change for the glow as well as size.
        glowAlphaFinish = max(
            glowAlphaStart,
            min(clampedVelocity * VELOCITY_GLOW_FACTOR * .00001f, MAX_ALPHA)
        )
        targetDisplacement = 0.5f
    }

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

        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.height() - radius

        canvas.translate(-centerX, 0f)
        canvas.scale(1f, min(glowScaleY, 1f) * baseGlowScale, 0f)
        canvas.translate(centerX, 0f)

        val displacement = max(0f, min(displacement, 1f)) - 0.5f
        val translateX = bounds.width() * displacement / 2

        canvas.translate(translateX, 0f)
        canvas.fillOval(centerX, centerY, radius, radius, color)
        canvas.restore()

        var oneLastFrame = false
        if (state == STATE_RECEDE && glowScaleY == 0f) {
            state = STATE_IDLE
            oneLastFrame = true
        }

        return state != STATE_IDLE || oneLastFrame
    }

    private fun update() {
        val time = AnimationUtils.currentAnimationTimeMillis()
        val t = min((time - startTime).toFloat() / duration, 1f)

        val interp = interpolator.getInterpolation(t)

        glowAlpha = glowAlphaStart + (glowAlphaFinish - glowAlphaStart) * interp
        glowScaleY = glowScaleYStart + (glowScaleYFinish - glowScaleYStart) * interp
        displacement = (displacement + targetDisplacement) / 2

        if (t >= 1f - EPSILON) {
            when (state) {
                STATE_ABSORB -> {
                    state = STATE_RECEDE
                    startTime = AnimationUtils.currentAnimationTimeMillis()
                    duration = RECEDE_TIME.toFloat()

                    glowAlphaStart = glowAlpha
                    glowScaleYStart = glowScaleY

                    // After absorb, the glow should fade to nothing.
                    glowAlphaFinish = 0f
                    glowScaleYFinish = 0f
                }

                STATE_PULL -> {
                    state = STATE_PULL_DECAY
                    startTime = AnimationUtils.currentAnimationTimeMillis()
                    duration = PULL_DECAY_TIME.toFloat()

                    glowAlphaStart = glowAlpha
                    glowScaleYStart = glowScaleY

                    // After pull, the glow should fade to nothing.
                    glowAlphaFinish = 0f
                    glowScaleYFinish = 0f
                }

                STATE_PULL_DECAY -> state = STATE_RECEDE
                STATE_RECEDE -> state = STATE_IDLE
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
