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

@file:Suppress("UseCheckOrError", "UnsafeCallOnNullableType") // TODO(2.4-idiom)

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
// isContentValid() is meaningful only when the isLoaded() returns true.
// It means whether the content needs to be updated.
//
// The user of this class should call recycle() when the texture is not
// needed anymore.
//
// By default an UploadedTexture is opaque (so it can be drawn faster without
// blending). The user or subclass can override it using setOpaque().
abstract class UploadedTexture protected constructor(hasBorder: Boolean) :
    BasicTexture(null, 0, STATE_UNLOADED) {

    private var mContentValid = true

    // indicate this textures is being uploaded in background
    private var mIsUploading = false
    private var mOpaque = true
    private var mThrottled = false

    @JvmField
    protected var mBitmap: Bitmap? = null
    private var mBorder = 0

    init {
        if (hasBorder) {
            setBorder(true)
            mBorder = 1
        }
    }

    protected constructor() : this(false)

    protected fun setIsUploading(uploading: Boolean) {
        mIsUploading = uploading
    }

    fun isUploading(): Boolean {
        return mIsUploading
    }

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
        mThrottled = throttled
    }

    private fun getBitmap(): Bitmap {
        var bitmap = mBitmap
        if (bitmap == null) {
            bitmap = onGetBitmap()
            mBitmap = bitmap
            if (mWidth == UNSPECIFIED) {
                val w = bitmap.width + mBorder * 2
                val h = bitmap.height + mBorder * 2
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
        mContentValid = false
        mWidth = UNSPECIFIED
        mHeight = UNSPECIFIED
    }

    /**
     * Whether the content on GPU is valid.
     */
    fun isContentValid(): Boolean {
        return isLoaded() && mContentValid
    }

    /**
     * Updates the content on GPU's memory.
     * @param canvas
     */
    fun updateContent(canvas: GLCanvas) {
        if (!isLoaded()) {
            if (mThrottled && ++sUploadedCount > UPLOAD_LIMIT) {
                return
            }
            uploadToCanvas(canvas)
        } else if (!mContentValid) {
            val bitmap = getBitmap()
            val format = GLUtils.getInternalFormat(bitmap)
            val type = GLUtils.getType(bitmap)
            canvas.texSubImage2D(this, mBorder, mBorder, bitmap, format, type)
            freeBitmap()
            mContentValid = true
        }
    }

    private fun uploadToCanvas(canvas: GLCanvas) {
        // onGetBitmap() never returns null, so the "no bitmap" error branch of
        // the Java version could not run.
        val bitmap = getBitmap()
        try {
            val bWidth = bitmap.width
            val bHeight = bitmap.height
            val texWidth = getTextureWidth()
            val texHeight = getTextureHeight()

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
                val config = bitmap.config

                canvas.initializeTextureSize(this, format, type)
                canvas.texSubImage2D(this, mBorder, mBorder, bitmap, format, type)

                if (mBorder > 0) {
                    // Left border
                    var line = getBorderLine(true, config, texHeight)
                    canvas.texSubImage2D(this, 0, 0, line, format, type)

                    // Top border
                    line = getBorderLine(false, config, texWidth)
                    canvas.texSubImage2D(this, 0, 0, line, format, type)
                }

                // Right border
                if (mBorder + bWidth < texWidth) {
                    val line = getBorderLine(true, config, texHeight)
                    canvas.texSubImage2D(this, mBorder + bWidth, 0, line, format, type)
                }

                // Bottom border
                if (mBorder + bHeight < texHeight) {
                    val line = getBorderLine(false, config, texWidth)
                    canvas.texSubImage2D(this, 0, mBorder + bHeight, line, format, type)
                }
            }
        } finally {
            freeBitmap()
        }
        // Update texture state.
        setAssociatedCanvas(canvas)
        mState = STATE_LOADED
        mContentValid = true
    }

    override fun onBind(canvas: GLCanvas): Boolean {
        updateContent(canvas)
        return isContentValid()
    }

    override fun getTarget(): Int {
        return GL11.GL_TEXTURE_2D
    }

    fun setOpaque(isOpaque: Boolean) {
        mOpaque = isOpaque
    }

    override fun isOpaque(): Boolean {
        return mOpaque
    }

    override fun recycle() {
        super.recycle()
        if (mBitmap != null) freeBitmap()
    }

    companion object {
        // To prevent keeping allocation the borders, we store those used borders here.
        // Since the length will be power of two, it won't use too much memory.
        private val sBorderLines = HashMap<BorderKey, Bitmap>()
        private val sBorderKey = BorderKey()

        private var sUploadedCount = 0
        private const val UPLOAD_LIMIT = 100

        private fun getBorderLine(vertical: Boolean, config: Bitmap.Config?, length: Int): Bitmap {
            val key = sBorderKey
            key.vertical = vertical
            key.config = config
            key.length = length
            var bitmap = sBorderLines[key]
            if (bitmap == null) {
                bitmap = if (vertical) {
                    Bitmap.createBitmap(1, length, config!!)
                } else {
                    Bitmap.createBitmap(length, 1, config!!)
                }
                sBorderLines[key.clone()] = bitmap
            }
            return bitmap
        }

        @JvmStatic
        fun resetUploadLimit() {
            sUploadedCount = 0
        }

        @JvmStatic
        fun uploadLimitReached(): Boolean {
            return sUploadedCount > UPLOAD_LIMIT
        }
    }
}
