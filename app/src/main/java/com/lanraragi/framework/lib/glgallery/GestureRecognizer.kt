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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

// This class aggregates three gesture detectors: GestureDetector,
// ScaleGestureDetector, and DownUpDetector.
internal class GestureRecognizer(context: Context, private val listener: Listener) {

    interface Listener {
        fun onSingleTapUp(x: Float, y: Float): Boolean
        fun onSingleTapConfirmed(x: Float, y: Float): Boolean
        fun onDoubleTap(x: Float, y: Float): Boolean
        fun onDoubleTapConfirmed(x: Float, y: Float): Boolean
        fun onLongPress(x: Float, y: Float)
        fun onScroll(dx: Float, dy: Float, totalX: Float, totalY: Float, x: Float, y: Float): Boolean

        /**
         * @param velocityX Finger from top to bottom is positive
         * @param velocityY Finger from left to right is positive
         */
        fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean
        fun onScaleBegin(focusX: Float, focusY: Float): Boolean
        fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean
        fun onScaleEnd()
        fun onDown(x: Float, y: Float)
        fun onUp()
        fun onPointerDown(x: Float, y: Float)
        fun onPointerUp()
    }

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector
    private val downUpDetector: DownUpDetector

    init {
        val gestureListener = MyGestureListener()
        gestureDetector = GestureDetector(context, gestureListener, null)
        gestureDetector.setOnDoubleTapListener(gestureListener)
        scaleDetector = ScaleGestureDetector(context, MyScaleListener())
        downUpDetector = DownUpDetector(MyDownUpListener())
    }

    fun onTouchEvent(event: MotionEvent) {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        downUpDetector.onTouchEvent(event)
    }

    private inner class MyGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean = listener.onSingleTapUp(e.x, e.y)

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = listener.onSingleTapConfirmed(e.x, e.y)

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            return if (e.action == MotionEvent.ACTION_UP) {
                listener.onDoubleTapConfirmed(e.x, e.y)
            } else {
                true
            }
        }

        override fun onDoubleTap(e: MotionEvent): Boolean = listener.onDoubleTap(e.x, e.y)

        override fun onLongPress(e: MotionEvent) {
            listener.onLongPress(e.x, e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // No down event to measure the scroll from (the detector missed
            // the gesture's start): leave the move unhandled.
            if (e1 == null) return false
            return listener.onScroll(dx, dy, e2.x - e1.x, e2.y - e1.y, e2.x, e2.y)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            return listener.onFling(e1, e2, velocityX, velocityY)
        }
    }

    private inner class MyScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return listener.onScaleBegin(detector.focusX, detector.focusY)
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return listener.onScale(detector.focusX, detector.focusY, detector.scaleFactor)
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            listener.onScaleEnd()
        }
    }

    private inner class MyDownUpListener : DownUpDetector.DownUpListener {
        override fun onDown(e: MotionEvent) {
            listener.onDown(e.x, e.y)
        }

        override fun onUp(e: MotionEvent) {
            listener.onUp()
        }

        override fun onPointerDown(e: MotionEvent) {
            listener.onPointerDown(e.x, e.y)
        }

        override fun onPointerUp(e: MotionEvent) {
            listener.onPointerUp()
        }
    }
}
