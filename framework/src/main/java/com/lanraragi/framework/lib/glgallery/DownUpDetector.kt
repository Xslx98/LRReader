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

import android.view.MotionEvent

internal class DownUpDetector(private val listener: DownUpListener) {
    interface DownUpListener {
        fun onDown(e: MotionEvent)
        fun onUp(e: MotionEvent)
        fun onPointerDown(e: MotionEvent)
        fun onPointerUp(e: MotionEvent)
    }

    private var stillDown = false

    private fun setState(down: Boolean, e: MotionEvent) {
        if (down == stillDown) return
        stillDown = down
        if (down) {
            listener.onDown(e)
        } else {
            listener.onUp(e)
        }
    }

    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> setState(true, ev)
            MotionEvent.ACTION_POINTER_DOWN -> listener.onPointerDown(ev)
            MotionEvent.ACTION_UP -> {
                listener.onPointerUp(ev)
                setState(false, ev)
            }
            MotionEvent.ACTION_CANCEL -> setState(false, ev)
        }
    }
}
