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
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.FloatBuffer

//
// GLCanvas gives a convenient interface to draw using OpenGL.
//
// When a rectangle is specified in this interface, it means the region
// [x, x+width) * [y, y+height)
//
interface GLCanvas {

    fun getGLId(): GLId

    // Tells GLCanvas the size of the underlying GL surface. This should be
    // called before first drawing and when the size of GL surface is changed.
    // This is called by GLRoot and should not be called by the clients
    // who only want to draw on the GLCanvas. Both width and height must be
    // nonnegative.
    fun setSize(width: Int, height: Int)

    // Clear the drawing buffers. This should only be used by GLRoot.
    fun clearBuffer()

    fun clearBuffer(argb: FloatArray)

    // Sets and gets the current alpha, alpha must be in [0, 1].
    fun setAlpha(alpha: Float)

    fun getAlpha(): Float

    // (current alpha) = (current alpha) * alpha
    fun multiplyAlpha(alpha: Float)

    // Change the current transform matrix.
    fun translate(x: Float, y: Float, z: Float)

    fun translate(x: Float, y: Float)

    fun scale(sx: Float, sy: Float, sz: Float)

    fun rotate(angle: Float, x: Float, y: Float, z: Float)

    fun multiplyMatrix(matrix: FloatArray, offset: Int)

    // Pushes the configuration state (matrix, and alpha) onto
    // a private stack.
    fun save()

    // Same as save(), but only save those specified in saveFlags.
    fun save(saveFlags: Int)

    // Pops from the top of the stack as current configuration state (matrix,
    // alpha, and clip). This call balances a previous call to save(), and is
    // used to remove all modifications to the configuration state since the
    // last save call.
    fun restore()

    // Draws a line using the specified paint from (x1, y1) to (x2, y2).
    // (Both end points are included).
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, paint: GLPaint)

    // Draws a rectangle outline using the specified paint at (x, y) with the
    // given width and height.
    fun drawRect(x: Float, y: Float, width: Float, height: Float, paint: GLPaint)

    // Draws a oval using the specified paint for (cx, cy, radiusX, radiusY)
    // (Both end points are included).
    fun drawOval(cx: Float, cy: Float, radiusX: Float, radiusY: Float, paint: GLPaint)

    // Draw a arc inside a rect
    fun drawArc(cx: Float, cy: Float, radiusX: Float, radiusY: Float, sweepAngle: Float, paint: GLPaint)

    // Fills the specified rectangle with the specified color.
    fun fillRect(x: Float, y: Float, width: Float, height: Float, color: Int)

    // Fills the specified oval with the specified color.
    fun fillOval(cx: Float, cy: Float, radiusX: Float, radiusY: Float, color: Int)

    // Fills the specified sector with the specified color.
    fun fillSector(cx: Float, cy: Float, radiusX: Float, radiusY: Float, sweepAngle: Float, color: Int)

    // Draws a texture to the specified rectangle.
    fun drawTexture(texture: BasicTexture, x: Int, y: Int, width: Int, height: Int)

    fun drawMesh(texture: BasicTexture, x: Int, y: Int, xyBuffer: Int, uvBuffer: Int, indexBuffer: Int, indexCount: Int)

    // Draws the source rectangle part of the texture to the target rectangle.
    fun drawTexture(texture: BasicTexture, source: RectF, target: RectF)

    // Draw a texture with a specified texture transform.
    fun drawTexture(texture: BasicTexture, textureTransform: FloatArray, x: Int, y: Int, w: Int, h: Int)

    // Draw two textures to the specified rectangle. The actual texture used is
    // from * (1 - ratio) + to * ratio
    // The two textures must have the same size.
    fun drawMixed(texture: BasicTexture, toColor: Int, ratio: Float, x: Int, y: Int, w: Int, h: Int)

    // Draw a region of a texture and a specified color to the specified
    // rectangle. The actual color used is from * (1 - ratio) + to * ratio.
    // The region of the texture is defined by parameter "source". The target
    // rectangle is specified by parameter "target".
    fun drawMixed(texture: BasicTexture, toColor: Int, ratio: Float, source: RectF, target: RectF)

    // Unloads the specified texture from the canvas. The resource allocated
    // to draw the texture will be released. The specified texture will return
    // to the unloaded state. This function should be called only from
    // BasicTexture or its descendant
    fun unloadTexture(texture: BasicTexture): Boolean

    // Delete the specified buffer object, similar to unloadTexture.
    fun deleteBuffer(bufferId: Int)

    // Delete the textures and buffers in GL side. This function should only be
    // called in the GL thread.
    fun deleteRecycledResources()

    // Dump statistics information and clear the counters. For debug only.
    fun dumpStatisticsAndClear()

    /**
     * Sets texture parameters to use GL_CLAMP_TO_EDGE for both
     * GL_TEXTURE_WRAP_S and GL_TEXTURE_WRAP_T. Sets texture parameters to be
     * GL_LINEAR for GL_TEXTURE_MIN_FILTER and GL_TEXTURE_MAG_FILTER.
     * bindTexture() must be called prior to this.
     *
     * @param texture The texture to set parameters on.
     */
    fun setTextureParameters(texture: BasicTexture)

    /**
     * Initializes the texture to a size by calling texImage2D on it.
     *
     * @param texture The texture to initialize the size.
     * @param format The texture format (e.g. GL_RGBA)
     * @param type The texture type (e.g. GL_UNSIGNED_BYTE)
     */
    fun initializeTextureSize(texture: BasicTexture, format: Int, type: Int)

    /**
     * Initializes the texture to a size by calling texImage2D on it.
     *
     * @param texture The texture to initialize the size.
     * @param bitmap The bitmap to initialize the bitmap with.
     */
    fun initializeTexture(texture: BasicTexture, bitmap: Bitmap)

    /**
     * Calls glTexSubImage2D to upload a bitmap to the texture.
     *
     * @param texture The target texture to write to.
     * @param xOffset Specifies a texel offset in the x direction within the
     *            texture array.
     * @param yOffset Specifies a texel offset in the y direction within the
     *            texture array.
     * @param format The texture format (e.g. GL_RGBA)
     * @param type The texture type (e.g. GL_UNSIGNED_BYTE)
     */
    fun texSubImage2D(texture: BasicTexture, xOffset: Int, yOffset: Int, bitmap: Bitmap, format: Int, type: Int)

    /**
     * Generates buffers and uploads the buffer data.
     *
     * @param buffer The buffer to upload
     * @return The buffer ID that was generated.
     */
    fun uploadBuffer(buffer: FloatBuffer): Int

    /**
     * Generates buffers and uploads the element array buffer data.
     *
     * @param buffer The buffer to upload
     * @return The buffer ID that was generated.
     */
    fun uploadBuffer(buffer: ByteBuffer): Int

    /**
     * After LightCycle makes GL calls, this method is called to restore the GL
     * configuration to the one expected by GLCanvas.
     */
    fun recoverFromLightCycle()

    /**
     * Gets the bounds given by x, y, width, and height as well as the internal
     * matrix state. There is no special handling for non-90-degree rotations.
     * It only considers the lower-left and upper-right corners as the bounds.
     *
     * @param bounds The output bounds to write to.
     * @param x The left side of the input rectangle.
     * @param y The bottom of the input rectangle.
     * @param width The width of the input rectangle.
     * @param height The height of the input rectangle.
     */
    fun getBounds(bounds: Rect, x: Int, y: Int, width: Int, height: Int)

    companion object {
        const val SAVE_FLAG_ALL = -0x1 // 0xFFFFFFFF
        const val SAVE_FLAG_ALPHA = 0x01
        const val SAVE_FLAG_MATRIX = 0x02
    }
}
