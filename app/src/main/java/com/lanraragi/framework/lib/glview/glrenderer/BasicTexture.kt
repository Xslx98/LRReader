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

@file:Suppress( // TODO(2.4-idiom)
    "ThrowingExceptionsWithoutMessageOrCause",
    "FunctionNaming",
    "ImplicitDefaultLocale",
    "FunctionOnlyReturningConstant",
)

package com.lanraragi.framework.lib.glview.glrenderer

import android.graphics.RectF
import android.util.Log
import com.lanraragi.reader.util.MathUtils
import java.util.WeakHashMap

// BasicTexture is a Texture corresponds to a real GL texture.
// The state of a BasicTexture indicates whether its data is loaded to GL memory.
// If a BasicTexture is loaded into GL memory, it has a GL texture id.
abstract class BasicTexture protected constructor(canvas: GLCanvas?, id: Int, state: Int) : Texture {

    @JvmField
    protected var mId = -1

    @JvmField
    protected var mState = 0

    @JvmField
    protected var mWidth = UNSPECIFIED

    @JvmField
    protected var mHeight = UNSPECIFIED

    @JvmField
    protected var mTextureWidth = 0

    @JvmField
    protected var mTextureHeight = 0

    private var mHasBorder = false

    @JvmField
    protected var mCanvasRef: GLCanvas? = null

    init {
        setAssociatedCanvas(canvas)
        mId = id
        mState = state
        synchronized(sAllTextures) {
            sAllTextures.put(this, null)
        }
    }

    protected constructor() : this(null, 0, STATE_UNLOADED)

    protected fun setAssociatedCanvas(canvas: GLCanvas?) {
        mCanvasRef = canvas
    }

    /**
     * Sets the content size of this texture. In OpenGL, the actual texture
     * size must be of power of 2, the size of the content may be smaller.
     */
    fun setSize(width: Int, height: Int) {
        mWidth = width
        mHeight = height
        mTextureWidth = if (width > 0) MathUtils.nextPowerOf2(width) else 0
        mTextureHeight = if (height > 0) MathUtils.nextPowerOf2(height) else 0
        if (mTextureWidth > MAX_TEXTURE_SIZE || mTextureHeight > MAX_TEXTURE_SIZE) {
            Log.w(
                TAG, String.format("texture is too large: %d x %d", mTextureWidth, mTextureHeight),
                Exception()
            )
        }
    }

    fun isFlippedVertically(): Boolean {
        return false
    }

    fun getId(): Int {
        return mId
    }

    override fun getWidth(): Int {
        return mWidth
    }

    override fun getHeight(): Int {
        return mHeight
    }

    // Returns the width rounded to the next power of 2.
    fun getTextureWidth(): Int {
        return mTextureWidth
    }

    // Returns the height rounded to the next power of 2.
    fun getTextureHeight(): Int {
        return mTextureHeight
    }

    // Returns true if the texture has one pixel transparent border around the
    // actual content. This is used to avoid jigged edges.
    //
    // The jigged edges appear because we use GL_CLAMP_TO_EDGE for texture wrap
    // mode (GL_CLAMP is not available in OpenGL ES), so a pixel partially
    // covered by the texture will use the color of the edge texel. If we add
    // the transparent border, the color of the edge texel will be mixed with
    // appropriate amount of transparent.
    //
    // Currently our background is black, so we can draw the thumbnails without
    // enabling blending.
    fun hasBorder(): Boolean {
        return mHasBorder
    }

    protected fun setBorder(hasBorder: Boolean) {
        mHasBorder = hasBorder
    }

    override fun draw(canvas: GLCanvas, x: Int, y: Int) {
        canvas.drawTexture(this, x, y, getWidth(), getHeight())
    }

    override fun draw(canvas: GLCanvas, x: Int, y: Int, w: Int, h: Int) {
        canvas.drawTexture(this, x, y, w, h)
    }

    override fun draw(canvas: GLCanvas, source: RectF, target: RectF) {
        canvas.drawTexture(this, source, target)
    }

    // onBind is called before GLCanvas binds this texture.
    // It should make sure the data is uploaded to GL memory.
    // Public: GLES20Canvas calls it, and Kotlin's protected (unlike Java's) does
    // not reach the rest of the package.
    abstract fun onBind(canvas: GLCanvas): Boolean

    // Returns the GL texture target for this texture (e.g. GL_TEXTURE_2D).
    // Public for GLES20Canvas, see onBind.
    abstract fun getTarget(): Int

    fun isLoaded(): Boolean {
        return mState == STATE_LOADED
    }

    // recycle() is called when the texture will never be used again,
    // so it can free all resources.
    open fun recycle() {
        freeResource()
    }

    // yield() is called when the texture will not be used temporarily,
    // so it can free some resources.
    // The default implementation unloads the texture from GL memory, so
    // the subclass should make sure it can reload the texture to GL memory
    // later, or it will have to override this method.
    open fun `yield`() {
        freeResource()
    }

    private fun freeResource() {
        val canvas = mCanvasRef
        if (canvas != null && mId != -1) {
            canvas.unloadTexture(this)
            mId = -1 // Don't free it again.
        }
        mState = STATE_UNLOADED
        setAssociatedCanvas(null)
    }

    // Object.finalize() does nothing, so there is no super call to keep.
    @Throws(Throwable::class)
    protected fun finalize() {
        sInFinalizer.set(BasicTexture::class.java)
        recycle()
        sInFinalizer.set(null)
    }

    companion object {
        private const val TAG = "BasicTexture"
        protected const val UNSPECIFIED = -1

        protected const val STATE_UNLOADED = 0
        protected const val STATE_LOADED = 1
        protected const val STATE_ERROR = -1

        // Log a warning if a texture is larger along a dimension
        private const val MAX_TEXTURE_SIZE = 4096

        private val sAllTextures = WeakHashMap<BasicTexture, Any?>()
        private val sInFinalizer = ThreadLocal<Class<*>?>()

        // This is for deciding if we can call Bitmap's recycle().
        // We cannot call Bitmap's recycle() in finalizer because at that point
        // the finalizer of Bitmap may already be called so recycle() will crash.
        @JvmStatic
        fun inFinalizer(): Boolean {
            return sInFinalizer.get() != null
        }

        @JvmStatic
        fun yieldAllTextures() {
            synchronized(sAllTextures) {
                for (t in sAllTextures.keys) {
                    t.`yield`()
                }
            }
        }

        @JvmStatic
        fun invalidateAllTextures() {
            synchronized(sAllTextures) {
                for (t in sAllTextures.keys) {
                    t.mState = STATE_UNLOADED
                    t.setAssociatedCanvas(null)
                }
            }
        }
    }
}
