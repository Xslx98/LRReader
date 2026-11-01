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

package com.lanraragi.framework.lib.glview.util

import android.graphics.Color

object GalleryUtils {

    @JvmStatic
    fun intColorToFloatARGBArray(from: Int): FloatArray = floatArrayOf(
        Color.alpha(from) / 255f,
        Color.red(from) / 255f,
        Color.green(from) / 255f,
        Color.blue(from) / 255f
    )

    @JvmStatic
    fun floatARGBArrayTointColor(from: FloatArray): Int = Color.argb(
        (from[0] * 255).toInt(), (from[1] * 255).toInt(), (from[2] * 255).toInt(), (from[3] * 255).toInt()
    )

    // Below are used the detect using database in the render thread. It only
    // works most of the time, but that's ok because it's for debugging only.

    @Volatile
    private var currentThread: Thread? = null

    @JvmStatic
    fun setRenderThread() {
        currentThread = Thread.currentThread()
    }

    @JvmStatic
    fun isRenderThread(): Boolean = currentThread === Thread.currentThread()

    @JvmStatic
    fun assertInRenderThread() {
        if (currentThread !== Thread.currentThread()) {
            throw AssertionError("Should not do this in render thread")
        }
    }
}
