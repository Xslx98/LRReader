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

@file:Suppress("UseCheckOrError") // TODO(2.4-idiom)

package com.lanraragi.framework.lib.glview.glrenderer

import android.graphics.Bitmap
import android.opengl.GLUtils
import javax.microedition.khronos.opengles.GL11

// UploadedTextures use a Bitmap for the content of the texture.
//
// Subclasses should implement onGetBitmap() to provide the Bitmap and
// implement onFreeBitmap(mBitmap) which will be called when the Bitmap
// is not needed anymore.
//
// isContentValid is meaningful only when isLoaded is true.
// It means whether the content needs to be updated.
//
// The user of this class should call recycle() when the texture is not
// needed anymore.
//
// By default an UploadedTexture is opaque (so it can be drawn faster without
// blending). The user or subclass can override it using setOpaque().
abstract class UploadedTexture protected constructor(hasBorder: Boolean) :
    BasicTexture(null, 0, STATE_UNLOADED) {

    private var contentValid = true

    // indicate this textures is being uploaded in background
    var isUploading = false
        protected set
    private var opaque = true
    private var throttled = false

    @JvmField
    protected var mBitmap: Bitmap? = null
    private var borderWidth = 0

    init {
        if (hasBorder) {
            setBorder(true)
            borderWidth = 1
        }
    }

    protected constructor() : this(false)

    private class BorderKey : Cloneable {
        @JvmField
        var vertical = false

        @JvmField
        var config: Bitmap.Config? = null

        @JvmField
        var length = 0

        override fun hashCode(): Int {
            val x = config.hashCode() xor length
            return if (vertical) x else -x
        }

        override fun equals(other: Any?): Boolean {
            if (other !is BorderKey) return false
            return vertical == other.vertical &&
                config == other.config && length == other.length
        }

        public override fun clone(): BorderKey {
            try {
                return super.clone() as BorderKey
            } catch (e: CloneNotSupportedException) {
                throw AssertionError(e)
            }
        }
    }

    protected fun setThrottled(throttled: Boolean) {
        this.throttled = throttled
    }

    private fun getBitmap(): Bitmap {
        var bitmap = mBitmap
        if (bitmap == null) {
            bitmap = onGetBitmap()
            mBitmap = bitmap
            if (mWidth == UNSPECIFIED) {
                val w = bitmap.width + borderWidth * 2
                val h = bitmap.height + borderWidth * 2
                setSize(w, h)
            }
        }
        return bitmap
    }

    private fun freeBitmap() {
        val bitmap = mBitmap ?: throw IllegalArgumentException("null == mBitmap")
        onFreeBitmap(bitmap)
        mBitmap = null
    }

    override fun getWidth(): Int {
        if (mWidth == UNSPECIFIED) getBitmap()
        return mWidth
    }

    override fun getHeight(): Int {
        if (mWidth == UNSPECIFIED) getBitmap()
        return mHeight
    }

    protected abstract fun onGetBitmap(): Bitmap

    protected abstract fun onFreeBitmap(bitmap: Bitmap)

    fun invalidateContent() {
        if (mBitmap != null) freeBitmap()
        contentValid = false
        mWidth = UNSPECIFIED
        mHeight = UNSPECIFIED
    }

    /**
     * Whether the content on GPU is valid.
     */
    val isContentValid: Boolean
        get() = isLoaded && contentValid

    /**
     * Updates the content on GPU's memory.
     * @param canvas
     */
    fun updateContent(canvas: GLCanvas) {
        if (!isLoaded) {
            if (throttled && ++uploadedCount > UPLOAD_LIMIT) {
                return
            }
            uploadToCanvas(canvas)
        } else if (!contentValid) {
            val bitmap = getBitmap()
            val format = GLUtils.getInternalFormat(bitmap)
            val type = GLUtils.getType(bitmap)
            canvas.texSubImage2D(this, borderWidth, borderWidth, bitmap, format, type)
            freeBitmap()
            contentValid = true
        }
    }

    private fun uploadToCanvas(canvas: GLCanvas) {
        // onGetBitmap() never returns null, so the "no bitmap" error branch of
        // the Java version could not run.
        val bitmap = getBitmap()
        try {
            val bWidth = bitmap.width
            val bHeight = bitmap.height
            val texWidth = textureWidth
            val texHeight = textureHeight

            if (bWidth > texWidth || bHeight > texHeight) {
                throw IllegalStateException("bWidth > texWidth || bHeight > texHeight")
            }
            // Upload the bitmap to a new texture.
            mId = canvas.getGLId().generateTexture()
            canvas.setTextureParameters(this)

            if (bWidth == texWidth && bHeight == texHeight) {
                canvas.initializeTexture(this, bitmap)
            } else {
                val format = GLUtils.getInternalFormat(bitmap)
                val type = GLUtils.getType(bitmap)
                // Java failed in the border-line cache on a null config;
                // CanvasTexture's bitmaps are always ARGB_8888.
                val config = checkNotNull(bitmap.config) { "bitmap without a config" }

                canvas.initializeTextureSize(this, format, type)
                canvas.texSubImage2D(this, borderWidth, borderWidth, bitmap, format, type)

                if (borderWidth > 0) {
                    // Left border
                    var line = getBorderLine(true, config, texHeight)
                    canvas.texSubImage2D(this, 0, 0, line, format, type)

                    // Top border
                    line = getBorderLine(false, config, texWidth)
                    canvas.texSubImage2D(this, 0, 0, line, format, type)
                }

                // Right border
                if (borderWidth + bWidth < texWidth) {
                    val line = getBorderLine(true, config, texHeight)
                    canvas.texSubImage2D(this, borderWidth + bWidth, 0, line, format, type)
                }

                // Bottom border
                if (borderWidth + bHeight < texHeight) {
                    val line = getBorderLine(false, config, texWidth)
                    canvas.texSubImage2D(this, 0, borderWidth + bHeight, line, format, type)
                }
            }
        } finally {
            freeBitmap()
        }
        // Update texture state.
        setAssociatedCanvas(canvas)
        mState = STATE_LOADED
        contentValid = true
    }

    override fun onBind(canvas: GLCanvas): Boolean {
        updateContent(canvas)
        return isContentValid
    }

    override fun getTarget(): Int {
        return GL11.GL_TEXTURE_2D
    }

    fun setOpaque(isOpaque: Boolean) {
        opaque = isOpaque
    }

    override fun isOpaque(): Boolean {
        return opaque
    }

    override fun recycle() {
        super.recycle()
        if (mBitmap != null) freeBitmap()
    }

    companion object {
        // To prevent keeping allocation the borders, we store those used borders here.
        // Since the length will be power of two, it won't use too much memory.
        private val borderLines = HashMap<BorderKey, Bitmap>()
        private val borderKey = BorderKey()

        private var uploadedCount = 0
        private const val UPLOAD_LIMIT = 100

        private fun getBorderLine(vertical: Boolean, config: Bitmap.Config, length: Int): Bitmap {
            val key = borderKey
            key.vertical = vertical
            key.config = config
            key.length = length
            var bitmap = borderLines[key]
            if (bitmap == null) {
                bitmap = if (vertical) {
                    Bitmap.createBitmap(1, length, config)
                } else {
                    Bitmap.createBitmap(length, 1, config)
                }
                borderLines[key.clone()] = bitmap
            }
            return bitmap
        }

        @JvmStatic
        fun resetUploadLimit() {
            uploadedCount = 0
        }

        @JvmStatic
        fun uploadLimitReached(): Boolean {
            return uploadedCount > UPLOAD_LIMIT
        }
    }
}
