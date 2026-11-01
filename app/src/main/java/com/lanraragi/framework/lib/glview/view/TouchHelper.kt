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

@file:Suppress("CyclomaticComplexMethod", "NestedBlockDepth") // TODO(2.4-idiom)

package com.lanraragi.framework.lib.glview.view

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.lanraragi.reader.util.ViewUtils

class TouchHelper(private val mOwner: TouchOwner) {

    private var mPrePressed = false
    private var mHasPerformedLongPress = false

    private var mPendingCheckForLongPress: CheckForLongPress? = null
    private var mPendingCheckForTap: CheckForTap? = null
    private var mPerformClick: PerformClick? = null
    private var mUnsetPressedState: UnsetPressedState? = null

    fun onTouch(event: MotionEvent): Boolean {
        val owner = mOwner
        val x = event.x
        val y = event.y

        if (event.action == MotionEvent.ACTION_DOWN ||
            event.action == MotionEvent.ACTION_MOVE
        ) {
            owner.setHotspot(x, y)
        }

        if (!owner.isEnabled) {
            if (event.action == MotionEvent.ACTION_UP && owner.isPressed) {
                owner.isPressed = false
            }
            // A disabled view that is clickable still consumes the touch
            // events, it just doesn't respond to them.
            return owner.isClickable || owner.isLongClickable
        }

        if (owner.isClickable || owner.isLongClickable) {
            when (event.action) {
                MotionEvent.ACTION_UP -> if (owner.isPressed || mPrePressed) {
                    if (mPrePressed) {
                        // The button is being released before we actually
                        // showed it as pressed.  Make it show the pressed
                        // state now (before scheduling the click) to ensure
                        // the user sees it.
                        setPressed(true, x, y)
                    }

                    if (!mHasPerformedLongPress) {
                        // This is a tap, so remove the longpress check
                        removeLongPressCallback()

                        // Use a Runnable and post this rather than calling
                        // performClick directly. This lets other visual state
                        // of the view update before click actions start.
                        var performClick = mPerformClick
                        if (performClick == null) {
                            performClick = PerformClick()
                            mPerformClick = performClick
                        }
                        if (!MAIN_HANDLER.post(performClick)) {
                            owner.performClick()
                        }
                    }

                    var unsetPressedState = mUnsetPressedState
                    if (unsetPressedState == null) {
                        unsetPressedState = UnsetPressedState()
                        mUnsetPressedState = unsetPressedState
                    }

                    if (mPrePressed) {
                        MAIN_HANDLER.postDelayed(
                            unsetPressedState,
                            ViewConfiguration.getPressedStateDuration().toLong()
                        )
                    } else if (!MAIN_HANDLER.post(unsetPressedState)) {
                        // If the post failed, unpress right now
                        unsetPressedState.run()
                    }

                    removeTapCallback()
                }

                MotionEvent.ACTION_DOWN -> {
                    mHasPerformedLongPress = false

                    mPrePressed = true
                    var checkForTap = mPendingCheckForTap
                    if (checkForTap == null) {
                        checkForTap = CheckForTap()
                        mPendingCheckForTap = checkForTap
                    }
                    checkForTap.x = event.x
                    checkForTap.y = event.y
                    MAIN_HANDLER.postDelayed(checkForTap, ViewConfiguration.getTapTimeout().toLong())
                }

                MotionEvent.ACTION_CANCEL -> {
                    owner.isPressed = false
                    removeTapCallback()
                    removeLongPressCallback()
                }

                MotionEvent.ACTION_MOVE -> {
                    owner.setHotspot(x, y)

                    // Be lenient about moving outside of buttons
                    if (!pointInView(x, y, sTouchSlop.toFloat())) {
                        // Outside button
                        removeTapCallback()
                        if (owner.isPressed) {
                            // Remove any future long press/tap checks
                            removeLongPressCallback()

                            owner.isPressed = false
                        }
                    }
                }
            }

            return true
        }

        return false
    }

    private fun setPressed(pressed: Boolean, x: Float, y: Float) {
        mOwner.isPressed = pressed
        if (pressed) {
            mOwner.setHotspot(x, y)
        }
    }

    /**
     * Utility method to determine whether the given point, in local coordinates,
     * is inside the view, where the area of the view is expanded by the slop factor.
     * This method is called while processing touch-move events to determine if the event
     * is still within the view.
     */
    fun pointInView(localX: Float, localY: Float, slop: Float): Boolean =
        localX >= -slop && localY >= -slop && localX < (mOwner.width + slop) &&
            localY < (mOwner.height + slop)

    /**
     * Remove the longpress detection timer.
     */
    private fun removeLongPressCallback() {
        val checkForLongPress = mPendingCheckForLongPress
        if (checkForLongPress != null) {
            MAIN_HANDLER.removeCallbacks(checkForLongPress)
        }
    }

    /**
     * Remove the tap detection timer.
     */
    private fun removeTapCallback() {
        val checkForTap = mPendingCheckForTap
        if (checkForTap != null) {
            mPrePressed = false
            MAIN_HANDLER.removeCallbacks(checkForTap)
        }
    }

    private fun checkForLongClick(delayOffset: Int) {
        if (mOwner.isLongClickable) {
            mHasPerformedLongPress = false

            var checkForLongPress = mPendingCheckForLongPress
            if (checkForLongPress == null) {
                checkForLongPress = CheckForLongPress()
                mPendingCheckForLongPress = checkForLongPress
            }
            MAIN_HANDLER.postDelayed(
                checkForLongPress,
                (ViewConfiguration.getLongPressTimeout() - delayOffset).toLong()
            )
        }
    }

    private inner class CheckForLongPress : Runnable {

        override fun run() {
            if (mOwner.isPressed) {
                if (mOwner.performLongClick()) {
                    mHasPerformedLongPress = true
                }
            }
        }
    }

    private inner class CheckForTap : Runnable {
        @JvmField
        var x = 0f

        @JvmField
        var y = 0f

        override fun run() {
            mPrePressed = false
            setPressed(true, x, y)
            checkForLongClick(ViewConfiguration.getTapTimeout())
        }
    }

    private inner class PerformClick : Runnable {

        override fun run() {
            mOwner.performClick()
        }
    }

    private inner class UnsetPressedState : Runnable {

        override fun run() {
            mOwner.isPressed = false
        }
    }

    companion object {

        private val MAIN_HANDLER = Handler(Looper.getMainLooper())

        /**
         * Cache the touch slop from the context that created the view.
         */
        private var sTouchSlop = 8

        @JvmStatic
        fun initialize(context: Context) {
            sTouchSlop = ViewUtils.dp2pix(context, 8f) // 8dp
        }
    }
}
