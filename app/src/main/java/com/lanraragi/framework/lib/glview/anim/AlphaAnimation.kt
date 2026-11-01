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

import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.reader.util.MathUtils

class AlphaAnimation(
    private val startAlpha: Float,
    private val endAlpha: Float
) : CanvasAnimation() {
    private var currentAlpha: Float = startAlpha

    override val canvasSaveFlags: Int
        get() = GLCanvas.SAVE_FLAG_ALPHA

    override fun apply(canvas: GLCanvas) {
        canvas.multiplyAlpha(currentAlpha)
    }

    override fun onCalculate(progress: Float) {
        currentAlpha = MathUtils.clamp(startAlpha + (endAlpha - startAlpha) * progress, 0f, 1f)
    }
}
