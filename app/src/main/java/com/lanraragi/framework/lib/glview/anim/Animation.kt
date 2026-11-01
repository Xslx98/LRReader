/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.lanraragi.framework.lib.glview.anim

import android.os.SystemClock
import android.view.animation.Interpolator
import com.lanraragi.reader.util.MathUtils

// Animation calculates a value according to the current input time.
//
// 1. First we need to use setDuration(int) to set the duration of the
//    animation. The duration is in milliseconds.
// 2. Then we should call start(). The actual start time is the first value
//    passed to calculate(long).
// 3. Each time we want to get an animation value, we call
//    calculate(long currentTimeMillis) to ask the Animation to calculate it.
//    The parameter passed to calculate(long) should be nonnegative.
// 4. Use get() to get that value.
//
// In step 3, onCalculate(float progress) is called so subclasses can calculate
// the value according to progress (progress is a value in [0,1]).
//
// Before onCalculate(float) is called, There is an optional interpolator which
// can change the progress value. The interpolator can be set by
// setInterpolator(Interpolator). If the interpolator is used, the value passed
// to onCalculate may be (for example, the overshoot effect).
//
// The isActive() method returns true after the animation start() is called and
// before calculate is passed a value which reaches the duration of the
// animation.
//
// The start() method can be called again to restart the Animation.
//
abstract class Animation {
    private var mStartTime = NO_ANIMATION
    private var mDuration: Long = 0
    private var mInterpolator: Interpolator? = null
    private var mRepeatCount = 0
    private var mRepeatMode = 0

    private var mRunnedCount = 0
    private var mLastFrameTime: Long = 0

    fun setInterpolator(interpolator: Interpolator?) {
        mInterpolator = interpolator
    }

    fun setDuration(duration: Long) {
        mDuration = duration
    }

    fun setRepeatCount(repeatCount: Int) {
        mRepeatCount = repeatCount
    }

    fun setRepeatMode(repeatMode: Int) {
        mRepeatMode = repeatMode
    }

    fun isRunning(): Boolean {
        return mStartTime > 0 || mStartTime == ANIMATION_START
    }

    fun getLastFrameTime(): Long {
        return mLastFrameTime
    }

    fun start() {
        if (mStartTime == NO_ANIMATION) {
            mStartTime = ANIMATION_START
            mRunnedCount = 0
            mLastFrameTime = 0
        }
    }

    fun startAt(time: Long) {
        start()
        setStartTime(time)
    }

    fun startNow() {
        start()
        setStartTime(SystemClock.uptimeMillis())
    }

    fun setStartTime(time: Long) {
        mStartTime = time
    }

    fun cancel() {
        mStartTime = NO_ANIMATION
    }

    fun reset() {
        mStartTime = ANIMATION_START
        mRunnedCount = 0
        mLastFrameTime = 0
    }

    // UPSTREAM-LEGACY (Gallery2/AOSP): repeat mode not implemented
    fun calculate(currentTimeMillis: Long): Boolean {
        if (mStartTime == NO_ANIMATION) {
            return false
        }
        if (mStartTime == ANIMATION_START) {
            mStartTime = currentTimeMillis
        }
        mLastFrameTime = currentTimeMillis

        val elapse = currentTimeMillis - mStartTime
        // Avoid NaN: a zero duration (e.g. animator duration scale 0) finishes at once
        val x = MathUtils.clamp(if (mDuration == 0L) 1.0f else elapse.toFloat() / mDuration.toFloat(), 0.0f, 1.0f)
        val i = mInterpolator
        onCalculate(if (i != null) i.getInterpolation(x) else x)

        // It is ok to call cancel() in onCalculate()
        if (mStartTime != NO_ANIMATION && elapse >= mDuration) {
            mRunnedCount++
            if (mRunnedCount >= mRepeatCount && mRepeatCount != INFINITE) {
                onFinish()
                mStartTime = NO_ANIMATION
            } else {
                mStartTime += elapse
            }
        }

        return mStartTime != NO_ANIMATION
    }

    protected abstract fun onCalculate(progress: Float)

    protected open fun onFinish() {
    }

    companion object {
        private const val ANIMATION_START = -1L
        private const val NO_ANIMATION = -2L

        /**
         * When the animation reaches the end and `repeatCount` is INFINITE
         * or a positive value, the animation restarts from the beginning.
         */
        const val RESTART = 1

        /**
         * When the animation reaches the end and `repeatCount` is INFINITE
         * or a positive value, the animation reverses direction on every iteration.
         */
        const val REVERSE = 2

        /**
         * This value used used with the [setRepeatCount] property to repeat
         * the animation indefinitely.
         */
        const val INFINITE = -1
    }
}
