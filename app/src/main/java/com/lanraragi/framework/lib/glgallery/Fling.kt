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

package com.lanraragi.framework.lib.glgallery

import android.content.Context
import android.hardware.SensorManager
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import com.lanraragi.framework.lib.glview.anim.Animation
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

internal abstract class Fling(context: Context) : Animation() {

    private val mPhysicalCoeff: Float

    init {
        val ppi = context.resources.displayMetrics.density * 160.0f
        mPhysicalCoeff = SensorManager.GRAVITY_EARTH * // g (m/s^2)
            39.37f * // inch/meter
            ppi *
            0.84f // look and feel tuning
        interpolator = FLING_INTERPOLATOR
    }

    private fun getSplineDeceleration(velocity: Int): Double {
        return ln((INFLEXION * abs(velocity) / (FLING_FRICTION * mPhysicalCoeff)).toDouble())
    }

    /* Returns the duration, expressed in milliseconds */
    protected fun getSplineFlingDuration(velocity: Int): Int {
        val l = getSplineDeceleration(velocity)
        val decelMinusOne = DECELERATION_RATE - 1.0
        return (1000.0 * exp(l / decelMinusOne)).toInt()
    }

    protected fun getSplineFlingDistance(velocity: Int): Double {
        val l = getSplineDeceleration(velocity)
        val decelMinusOne = DECELERATION_RATE - 1.0
        return (FLING_FRICTION * mPhysicalCoeff).toDouble() * exp(DECELERATION_RATE / decelMinusOne * l)
    }

    /**
     * Modifies mDuration to the duration it takes to get from start to newFinal using the
     * spline interpolation. The previous duration was needed to get to oldFinal.
     */
    protected fun adjustDuration(start: Int, oldFinal: Int, newFinal: Int, duration: Int): Int {
        var result = duration
        val oldDistance = oldFinal - start
        val newDistance = newFinal - start
        val x = abs(newDistance.toFloat() / oldDistance)
        val index = (NB_SAMPLES * x).toInt()
        if (index < NB_SAMPLES) {
            val xInf = index.toFloat() / NB_SAMPLES
            val xSup = (index + 1).toFloat() / NB_SAMPLES
            val tInf = SPLINE_TIME[index]
            val tSup = SPLINE_TIME[index + 1]
            val timeCoef = tInf + (x - xInf) / (xSup - xInf) * (tSup - tInf)
            result = (result * timeCoef).toInt()
        }
        return result
    }

    // Java's anonymous Fling$1. It reads its own reference to the position
    // table, so the per-frame getInterpolation needs no static accessor.
    private class FlingInterpolator(private val splinePosition: FloatArray) : Interpolator {
        override fun getInterpolation(input: Float): Float {
            val index = (NB_SAMPLES * input).toInt()
            var distanceCoef = 1f
            val velocityCoef: Float
            if (index < NB_SAMPLES) {
                val tInf = index.toFloat() / NB_SAMPLES
                val tSup = (index + 1).toFloat() / NB_SAMPLES
                val dInf = splinePosition[index]
                val dSup = splinePosition[index + 1]
                velocityCoef = (dSup - dInf) / (tSup - tInf)
                distanceCoef = dInf + (input - tInf) * velocityCoef
            }
            return distanceCoef
        }
    }

    companion object {
        private val DECELERATION_RATE = (ln(0.78) / ln(0.9)).toFloat()
        private const val INFLEXION = 0.35f // Tension lines cross at (INFLEXION, 1)
        private const val START_TENSION = 0.5f
        private const val END_TENSION = 1.0f
        private const val P1 = START_TENSION * INFLEXION
        private const val P2 = 1.0f - END_TENSION * (1.0f - INFLEXION)
        private val FLING_FRICTION = ViewConfiguration.getScrollFriction()

        private const val NB_SAMPLES = 100
        private val SPLINE_POSITION = FloatArray(NB_SAMPLES + 1)
        private val SPLINE_TIME = FloatArray(NB_SAMPLES + 1)

        init {
            var xMin = 0.0f
            var yMin = 0.0f
            for (i in 0 until NB_SAMPLES) {
                val alpha = i.toFloat() / NB_SAMPLES

                var xMax = 1.0f
                var x: Float
                var tx: Float
                var coef: Float
                while (true) {
                    x = xMin + (xMax - xMin) / 2.0f
                    coef = 3.0f * x * (1.0f - x)
                    tx = coef * ((1.0f - x) * P1 + x * P2) + x * x * x
                    if (abs(tx - alpha).toDouble() < 1E-5) break
                    if (tx > alpha) xMax = x else xMin = x
                }
                SPLINE_POSITION[i] = coef * ((1.0f - x) * START_TENSION + x) + x * x * x

                var yMax = 1.0f
                var y: Float
                var dy: Float
                while (true) {
                    y = yMin + (yMax - yMin) / 2.0f
                    coef = 3.0f * y * (1.0f - y)
                    dy = coef * ((1.0f - y) * START_TENSION + y) + y * y * y
                    if (abs(dy - alpha).toDouble() < 1E-5) break
                    if (dy > alpha) yMax = y else yMin = y
                }
                SPLINE_TIME[i] = coef * ((1.0f - y) * P1 + y * P2) + y * y * y
            }
            SPLINE_TIME[NB_SAMPLES] = 1.0f
            SPLINE_POSITION[NB_SAMPLES] = 1.0f
        }

        private val FLING_INTERPOLATOR: Interpolator = FlingInterpolator(SPLINE_POSITION)
    }
}
