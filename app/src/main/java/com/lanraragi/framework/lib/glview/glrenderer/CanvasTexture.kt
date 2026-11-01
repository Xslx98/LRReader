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

package com.lanraragi.framework.lib.glview.glrenderer

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap

// CanvasTexture is a texture whose content is the drawing on a Canvas.
// The subclasses should override onDraw() to draw on the bitmap.
// By default CanvasTexture is not opaque.
// Public (Java had it package-private): Kotlin has no package visibility and
// the public StringTexture extends it.
abstract class CanvasTexture(width: Int, height: Int) : UploadedTexture() {
    private val config: Bitmap.Config = Bitmap.Config.ARGB_8888

    init {
        setSize(width, height)
        setOpaque(false)
    }

    override fun onGetBitmap(): Bitmap {
        val bitmap = createBitmap(mWidth, mHeight, config)
        val canvas = Canvas(bitmap)
        onDraw(canvas, bitmap)
        return bitmap
    }

    override fun onFreeBitmap(bitmap: Bitmap) {
        if (!inFinalizer()) {
            bitmap.recycle()
        }
    }

    protected abstract fun onDraw(canvas: Canvas, backing: Bitmap)
}
