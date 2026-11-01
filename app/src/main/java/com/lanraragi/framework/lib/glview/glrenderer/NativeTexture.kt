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

@file:Suppress("ThrowingExceptionsWithoutMessageOrCause") // TODO(2.4-idiom)

package com.lanraragi.framework.lib.glview.glrenderer

import android.opengl.GLES20
import android.util.Log
import javax.microedition.khronos.opengles.GL11

abstract class NativeTexture : BasicTexture() {

    private var contentValid = true

    private var opaque = true

    fun invalidateContent() {
        contentValid = false
    }

    protected abstract fun texImage(init: Boolean)

    private fun uploadToCanvas(canvas: GLCanvas) {
        // Get id
        mId = canvas.getGLId().generateTexture()

        // Prepare
        canvas.setTextureParameters(this)

        // Call glTexImage2D
        GLES20.glBindTexture(getTarget(), mId)
        checkError()
        texImage(true)

        // Only mark as loaded if texImage succeeded (no GL error)
        val error = GLES20.glGetError()
        if (error == 0) {
            setAssociatedCanvas(canvas)
            mState = STATE_LOADED
            contentValid = true
        } else {
            Log.e(TAG, "texImage failed with GL error: $error, will retry")
            // Delete the texture so it can be re-created on next attempt
            GLES20.glDeleteTextures(1, intArrayOf(mId), 0)
            mId = 0
        }
    }

    fun updateContent(canvas: GLCanvas) {
        if (!isLoaded) {
            uploadToCanvas(canvas)
        } else if (!contentValid) {
            // Call glTexSubImage2D
            GLES20.glBindTexture(getTarget(), mId)
            checkError()
            texImage(false)
            // Only mark valid if no GL error
            val error = GLES20.glGetError()
            if (error == 0) {
                contentValid = true
            } else {
                Log.e(TAG, "texSubImage failed with GL error: $error")
            }
        }
    }

    /**
     * Whether the content on GPU is valid.
     */
    val isContentValid: Boolean
        get() = isLoaded && contentValid

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

    // A private member (Java: an unused public static) so the upload path
    // calls it directly instead of through a companion object.
    private fun checkError() {
        val error = GLES20.glGetError()
        if (error != 0) {
            val t = Throwable()
            Log.e(TAG, "GL error: $error", t)
        }
    }

    companion object {
        private val TAG = NativeTexture::class.java.simpleName
    }
}
