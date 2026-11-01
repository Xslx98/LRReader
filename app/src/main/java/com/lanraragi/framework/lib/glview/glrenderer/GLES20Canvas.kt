/*
 * Copyright (C) 2012 The Android Open Source Project
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
    "LongParameterList",
    "LargeClass",
    "TooGenericExceptionThrown",
    "ThrowingExceptionsWithoutMessageOrCause",
)

package com.lanraragi.framework.lib.glview.glrenderer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import com.lanraragi.reader.util.IntList
import com.lanraragi.reader.util.MathUtils
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class GLES20Canvas : GLCanvas {

    // Keep track of restore state
    private var mMatrices = FloatArray(INITIAL_RESTORE_STATE_SIZE * MATRIX_SIZE)
    private var mAlphas = FloatArray(INITIAL_RESTORE_STATE_SIZE)
    private val mSaveFlags = IntList()

    private var mCurrentAlphaIndex = 0
    private var mCurrentMatrixIndex = 0

    // Viewport size
    private var mWidth = 0
    private var mHeight = 0

    // Projection matrix
    private val mProjectionMatrix = FloatArray(MATRIX_SIZE)

    // GL programs
    private val mDrawProgram: Int
    private val mTextureProgram: Int
    private val mOesTextureProgram: Int
    private val mMeshProgram: Int

    // GL buffer containing BOX_COORDINATES
    private val mBoxCoordinates: Int

    private abstract class ShaderParameter(protected val mName: String) {
        @JvmField
        var handle = 0

        abstract fun loadHandle(program: Int)
    }

    private class UniformShaderParameter(name: String) : ShaderParameter(name) {
        override fun loadHandle(program: Int) {
            handle = GLES20.glGetUniformLocation(program, mName)
            checkGlError()
        }
    }

    private class AttributeShaderParameter(name: String) : ShaderParameter(name) {
        override fun loadHandle(program: Int) {
            handle = GLES20.glGetAttribLocation(program, mName)
            checkGlError()
        }
    }

    private val mDrawParameters = arrayOf<ShaderParameter>(
        AttributeShaderParameter(POSITION_ATTRIBUTE), // INDEX_POSITION
        UniformShaderParameter(MATRIX_UNIFORM), // INDEX_MATRIX
        UniformShaderParameter(COLOR_UNIFORM), // INDEX_COLOR
    )
    private val mTextureParameters = arrayOf<ShaderParameter>(
        AttributeShaderParameter(POSITION_ATTRIBUTE), // INDEX_POSITION
        UniformShaderParameter(MATRIX_UNIFORM), // INDEX_MATRIX
        UniformShaderParameter(TEXTURE_MATRIX_UNIFORM), // INDEX_TEXTURE_MATRIX
        UniformShaderParameter(TEXTURE_SAMPLER_UNIFORM), // INDEX_TEXTURE_SAMPLER
        UniformShaderParameter(ALPHA_UNIFORM), // INDEX_ALPHA
    )
    private val mOesTextureParameters = arrayOf<ShaderParameter>(
        AttributeShaderParameter(POSITION_ATTRIBUTE), // INDEX_POSITION
        UniformShaderParameter(MATRIX_UNIFORM), // INDEX_MATRIX
        UniformShaderParameter(TEXTURE_MATRIX_UNIFORM), // INDEX_TEXTURE_MATRIX
        UniformShaderParameter(TEXTURE_SAMPLER_UNIFORM), // INDEX_TEXTURE_SAMPLER
        UniformShaderParameter(ALPHA_UNIFORM), // INDEX_ALPHA
    )
    private val mMeshParameters = arrayOf<ShaderParameter>(
        AttributeShaderParameter(POSITION_ATTRIBUTE), // INDEX_POSITION
        UniformShaderParameter(MATRIX_UNIFORM), // INDEX_MATRIX
        AttributeShaderParameter(TEXTURE_COORD_ATTRIBUTE), // INDEX_TEXTURE_COORD
        UniformShaderParameter(TEXTURE_SAMPLER_UNIFORM), // INDEX_TEXTURE_SAMPLER
        UniformShaderParameter(ALPHA_UNIFORM), // INDEX_ALPHA
    )

    private val mUnboundTextures = IntList()
    private val mDeleteBuffers = IntList()

    // Keep track of statistics for debugging
    private var mCountDrawMesh = 0
    private var mCountTextureRect = 0
    private var mCountFillRect = 0
    private var mCountDrawLine = 0

    // Temporary variables used within calculations
    private val mTempMatrix = FloatArray(32)
    private val mTempColor = FloatArray(4)
    private val mTempSourceRect = RectF()
    private val mTempTargetRect = RectF()
    private val mTempTextureMatrix = FloatArray(MATRIX_SIZE)
    private val mTempIntArray = IntArray(1)

    // After every field above: the Java constructor ran after all field initializers.
    init {
        Matrix.setIdentityM(mTempTextureMatrix, 0)
        Matrix.setIdentityM(mMatrices, mCurrentMatrixIndex)
        mAlphas[mCurrentAlphaIndex] = 1f

        val boxBuffer = createBuffer(BOX_COORDINATES)
        mBoxCoordinates = uploadBuffer(boxBuffer)

        val drawVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, DRAW_VERTEX_SHADER)
        val textureVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, TEXTURE_VERTEX_SHADER)
        val meshVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, MESH_VERTEX_SHADER)
        val drawFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, DRAW_FRAGMENT_SHADER)
        val textureFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, TEXTURE_FRAGMENT_SHADER)
        val oesTextureFragmentShader = loadShader(
            GLES20.GL_FRAGMENT_SHADER,
            OES_TEXTURE_FRAGMENT_SHADER
        )

        mDrawProgram = assembleProgram(drawVertexShader, drawFragmentShader, mDrawParameters)
        mTextureProgram = assembleProgram(
            textureVertexShader, textureFragmentShader,
            mTextureParameters
        )
        mOesTextureProgram = assembleProgram(
            textureVertexShader, oesTextureFragmentShader,
            mOesTextureParameters
        )
        mMeshProgram = assembleProgram(meshVertexShader, textureFragmentShader, mMeshParameters)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        checkGlError()
    }

    private fun createBuffer(values: FloatArray): FloatBuffer {
        // First create an nio buffer, then create a VBO from it.
        val size = values.size * FLOAT_SIZE
        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(values, 0, values.size).position(0)
        return buffer
    }

    private fun assembleProgram(vertexShader: Int, fragmentShader: Int, params: Array<ShaderParameter>): Int {
        var program = GLES20.glCreateProgram()
        checkGlError()
        if (program == 0) {
            throw RuntimeException("Cannot create GL program: " + GLES20.glGetError())
        }
        GLES20.glAttachShader(program, vertexShader)
        checkGlError()
        GLES20.glAttachShader(program, fragmentShader)
        checkGlError()
        GLES20.glLinkProgram(program)
        checkGlError()
        val mLinkStatus = mTempIntArray
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, mLinkStatus, 0)
        if (mLinkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ")
            Log.e(TAG, GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            program = 0
        }
        for (i in params.indices) {
            params[i].loadHandle(program)
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
        // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
        val shader = GLES20.glCreateShader(type)

        // add the source code to the shader and compile it
        GLES20.glShaderSource(shader, shaderCode)
        checkGlError()
        GLES20.glCompileShader(shader)
        checkGlError()

        return shader
    }

    override fun setSize(width: Int, height: Int) {
        mWidth = width
        mHeight = height
        GLES20.glViewport(0, 0, mWidth, mHeight)
        checkGlError()
        Matrix.setIdentityM(mMatrices, mCurrentMatrixIndex)
        Matrix.orthoM(mProjectionMatrix, 0, 0f, width.toFloat(), 0f, height.toFloat(), -1f, 1f)
        Matrix.translateM(mMatrices, mCurrentMatrixIndex, 0f, height.toFloat(), 0f)
        Matrix.scaleM(mMatrices, mCurrentMatrixIndex, 1f, -1f, 1f)
    }

    override fun clearBuffer() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        checkGlError()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        checkGlError()
    }

    override fun clearBuffer(argb: FloatArray) {
        GLES20.glClearColor(argb[1], argb[2], argb[3], argb[0])
        checkGlError()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        checkGlError()
    }

    override fun getAlpha(): Float {
        return mAlphas[mCurrentAlphaIndex]
    }

    override fun setAlpha(alpha: Float) {
        mAlphas[mCurrentAlphaIndex] = alpha
    }

    override fun multiplyAlpha(alpha: Float) {
        setAlpha(getAlpha() * alpha)
    }

    override fun translate(x: Float, y: Float, z: Float) {
        Matrix.translateM(mMatrices, mCurrentMatrixIndex, x, y, z)
    }

    // This is a faster version of translate(x, y, z) because
    // (1) we knows z = 0, (2) we inline the Matrix.translateM call,
    // (3) we unroll the loop
    override fun translate(x: Float, y: Float) {
        val index = mCurrentMatrixIndex
        val m = mMatrices
        m[index + 12] += m[index + 0] * x + m[index + 4] * y
        m[index + 13] += m[index + 1] * x + m[index + 5] * y
        m[index + 14] += m[index + 2] * x + m[index + 6] * y
        m[index + 15] += m[index + 3] * x + m[index + 7] * y
    }

    override fun scale(sx: Float, sy: Float, sz: Float) {
        Matrix.scaleM(mMatrices, mCurrentMatrixIndex, sx, sy, sz)
    }

    override fun rotate(angle: Float, x: Float, y: Float, z: Float) {
        if (angle == 0f) {
            return
        }
        val temp = mTempMatrix
        Matrix.setRotateM(temp, 0, angle, x, y, z)
        val matrix = mMatrices
        val index = mCurrentMatrixIndex
        Matrix.multiplyMM(temp, MATRIX_SIZE, matrix, index, temp, 0)
        System.arraycopy(temp, MATRIX_SIZE, matrix, index, MATRIX_SIZE)
    }

    override fun multiplyMatrix(matrix: FloatArray, offset: Int) {
        val temp = mTempMatrix
        val currentMatrix = mMatrices
        val index = mCurrentMatrixIndex
        Matrix.multiplyMM(temp, 0, currentMatrix, index, matrix, offset)
        System.arraycopy(temp, 0, currentMatrix, index, 16)
    }

    override fun save() {
        save(GLCanvas.SAVE_FLAG_ALL)
    }

    override fun save(saveFlags: Int) {
        val saveAlpha = (saveFlags and GLCanvas.SAVE_FLAG_ALPHA) == GLCanvas.SAVE_FLAG_ALPHA
        if (saveAlpha) {
            val currentAlpha = getAlpha()
            mCurrentAlphaIndex++
            if (mAlphas.size <= mCurrentAlphaIndex) {
                mAlphas = mAlphas.copyOf(mAlphas.size * 2)
            }
            mAlphas[mCurrentAlphaIndex] = currentAlpha
        }
        val saveMatrix = (saveFlags and GLCanvas.SAVE_FLAG_MATRIX) == GLCanvas.SAVE_FLAG_MATRIX
        if (saveMatrix) {
            val currentIndex = mCurrentMatrixIndex
            mCurrentMatrixIndex += MATRIX_SIZE
            if (mMatrices.size <= mCurrentMatrixIndex) {
                mMatrices = mMatrices.copyOf(mMatrices.size * 2)
            }
            System.arraycopy(mMatrices, currentIndex, mMatrices, mCurrentMatrixIndex, MATRIX_SIZE)
        }
        mSaveFlags.add(saveFlags)
    }

    override fun restore() {
        val restoreFlags = mSaveFlags.removeAt(mSaveFlags.size() - 1)
        val restoreAlpha = (restoreFlags and GLCanvas.SAVE_FLAG_ALPHA) == GLCanvas.SAVE_FLAG_ALPHA
        if (restoreAlpha) {
            mCurrentAlphaIndex--
        }
        val restoreMatrix = (restoreFlags and GLCanvas.SAVE_FLAG_MATRIX) == GLCanvas.SAVE_FLAG_MATRIX
        if (restoreMatrix) {
            mCurrentMatrixIndex -= MATRIX_SIZE
        }
    }

    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, paint: GLPaint) {
        draw(
            GLES20.GL_LINE_STRIP, OFFSET_DRAW_LINE, COUNT_LINE_VERTEX, x1, y1, x2 - x1, y2 - y1,
            paint
        )
        mCountDrawLine++
    }

    override fun drawRect(x: Float, y: Float, width: Float, height: Float, paint: GLPaint) {
        draw(GLES20.GL_LINE_LOOP, OFFSET_DRAW_RECT, COUNT_RECT_VERTEX, x, y, width, height, paint)
        mCountDrawLine++
    }

    override fun drawOval(cx: Float, cy: Float, radiusX: Float, radiusY: Float, paint: GLPaint) {
        val halfLineWidth = paint.getLineWidth() / 2
        fillOval(cx, cy, radiusX + halfLineWidth, radiusY + halfLineWidth, paint.getColor())
        fillOval(cx, cy, radiusX - halfLineWidth, radiusY - halfLineWidth, paint.getBackgroundColor())
    }

    override fun drawArc(
        cx: Float,
        cy: Float,
        radiusX: Float,
        radiusY: Float,
        sweepAngle: Float,
        paint: GLPaint,
    ) {
        val halfLineWidth = paint.getLineWidth() / 2
        fillSector(cx, cy, radiusX + halfLineWidth, radiusY + halfLineWidth, sweepAngle, paint.getColor())
        fillOval(cx, cy, radiusX - halfLineWidth, radiusY - halfLineWidth, paint.getBackgroundColor())
    }

    private fun draw(
        type: Int,
        offset: Int,
        count: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        paint: GLPaint,
    ) {
        draw(type, offset, count, x, y, width, height, paint.getColor(), paint.getLineWidth())
    }

    private fun draw(
        type: Int,
        offset: Int,
        count: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int,
        lineWidth: Float,
    ) {
        prepareDraw(offset, color, lineWidth)
        draw(mDrawParameters, type, count, x, y, width, height)
    }

    private fun prepareDraw(offset: Int, color: Int, lineWidth: Float) {
        GLES20.glUseProgram(mDrawProgram)
        checkGlError()
        if (lineWidth > 0) {
            GLES20.glLineWidth(lineWidth)
            checkGlError()
        }
        val colorArray = getColor(color)
        val blendingEnabled = colorArray[3] < 1f
        enableBlending(blendingEnabled)
        if (blendingEnabled) {
            GLES20.glBlendColor(colorArray[0], colorArray[1], colorArray[2], colorArray[3])
            checkGlError()
        }
        GLES20.glUniform4fv(mDrawParameters[INDEX_COLOR].handle, 1, colorArray, 0)
        setPosition(mDrawParameters, offset)
        checkGlError()
    }

    private fun getColor(color: Int): FloatArray {
        val alpha = ((color ushr 24) and 0xFF) / 255f * getAlpha()
        val red = ((color ushr 16) and 0xFF) / 255f * alpha
        val green = ((color ushr 8) and 0xFF) / 255f * alpha
        val blue = (color and 0xFF) / 255f * alpha
        mTempColor[0] = red
        mTempColor[1] = green
        mTempColor[2] = blue
        mTempColor[3] = alpha
        return mTempColor
    }

    private fun enableBlending(enableBlending: Boolean) {
        if (enableBlending) {
            GLES20.glEnable(GLES20.GL_BLEND)
            checkGlError()
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
            checkGlError()
        }
    }

    private fun setPosition(params: Array<ShaderParameter>, offset: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mBoxCoordinates)
        checkGlError()
        GLES20.glVertexAttribPointer(
            params[INDEX_POSITION].handle, COORDS_PER_VERTEX,
            GLES20.GL_FLOAT, false, VERTEX_STRIDE, offset * VERTEX_STRIDE
        )
        checkGlError()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        checkGlError()
    }

    private fun draw(
        params: Array<ShaderParameter>,
        type: Int,
        count: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        setMatrix(params, x, y, width, height)
        val positionHandle = params[INDEX_POSITION].handle
        GLES20.glEnableVertexAttribArray(positionHandle)
        checkGlError()
        GLES20.glDrawArrays(type, 0, count)
        checkGlError()
        GLES20.glDisableVertexAttribArray(positionHandle)
        checkGlError()
    }

    private fun setMatrix(params: Array<ShaderParameter>, x: Float, y: Float, width: Float, height: Float) {
        Matrix.translateM(mTempMatrix, 0, mMatrices, mCurrentMatrixIndex, x, y, 0f)
        Matrix.scaleM(mTempMatrix, 0, width, height, 1f)
        Matrix.multiplyMM(mTempMatrix, MATRIX_SIZE, mProjectionMatrix, 0, mTempMatrix, 0)
        GLES20.glUniformMatrix4fv(params[INDEX_MATRIX].handle, 1, false, mTempMatrix, MATRIX_SIZE)
        checkGlError()
    }

    override fun fillRect(x: Float, y: Float, width: Float, height: Float, color: Int) {
        draw(
            GLES20.GL_TRIANGLE_STRIP, OFFSET_FILL_RECT, COUNT_FILL_VERTEX, x, y, width, height,
            color, 0f
        )
        mCountFillRect++
    }

    override fun fillOval(cx: Float, cy: Float, radiusX: Float, radiusY: Float, color: Int) {
        draw(
            GLES20.GL_TRIANGLE_FAN, OFFSET_FILL_CIRCLE, OFFSET_LAST - OFFSET_FILL_CIRCLE,
            cx - radiusX, cy - radiusY, radiusX * 2, radiusY * 2, color, 0f
        )
        mCountFillRect++
    }

    override fun fillSector(cx: Float, cy: Float, radiusX: Float, radiusY: Float, sweepAngle: Float, color: Int) {
        val conjugateAngle = abs(360 - MathUtils.positiveModulo(sweepAngle, 360f))
        val conjugateCount = Math.round(COUNT_CIRCLE_VERTEX * conjugateAngle / 360)
        if (conjugateCount == 0) {
            // It is a circle
            fillOval(cx, cy, radiusX, radiusY, color)
        } else if (conjugateCount < COUNT_CIRCLE_VERTEX) {
            draw(
                GLES20.GL_TRIANGLE_FAN, OFFSET_FILL_CIRCLE, OFFSET_LAST - OFFSET_FILL_CIRCLE - conjugateCount,
                cx - radiusX, cy - radiusY, radiusX * 2, radiusY * 2, color, 0f
            )
            mCountFillRect++
        }
    }

    override fun drawTexture(texture: BasicTexture, x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        copyTextureCoordinates(texture, mTempSourceRect)
        mTempTargetRect.set(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        convertCoordinate(mTempSourceRect, mTempTargetRect, texture)
        drawTextureRect(texture, mTempSourceRect, mTempTargetRect)
    }

    // A member, not a static: called on every texture draw, it should not go
    // through a companion object.
    private fun copyTextureCoordinates(texture: BasicTexture, outRect: RectF) {
        var left = 0
        var top = 0
        var right = texture.getWidth()
        var bottom = texture.getHeight()
        if (texture.hasBorder()) {
            left = 1
            top = 1
            right -= 1
            bottom -= 1
        }
        outRect.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    override fun drawTexture(texture: BasicTexture, source: RectF, target: RectF) {
        if (target.width() <= 0 || target.height() <= 0) {
            return
        }
        mTempSourceRect.set(source)
        mTempTargetRect.set(target)

        convertCoordinate(mTempSourceRect, mTempTargetRect, texture)
        drawTextureRect(texture, mTempSourceRect, mTempTargetRect)
    }

    override fun drawTexture(
        texture: BasicTexture,
        textureTransform: FloatArray,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        if (w <= 0 || h <= 0) {
            return
        }
        mTempTargetRect.set(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        drawTextureRect(texture, textureTransform, mTempTargetRect)
    }

    private fun drawTextureRect(texture: BasicTexture, source: RectF, target: RectF) {
        setTextureMatrix(source)
        drawTextureRect(texture, mTempTextureMatrix, target)
    }

    private fun setTextureMatrix(source: RectF) {
        mTempTextureMatrix[0] = source.width()
        mTempTextureMatrix[5] = source.height()
        mTempTextureMatrix[12] = source.left
        mTempTextureMatrix[13] = source.top
    }

    // This function changes the source coordinate to the texture coordinates.
    // It also clips the source and target coordinates if it is beyond the
    // bound of the texture.
    // A member, not a static: see copyTextureCoordinates.
    private fun convertCoordinate(source: RectF, target: RectF, texture: BasicTexture) {
        val width = texture.getWidth()
        val height = texture.getHeight()
        val texWidth = texture.getTextureWidth()
        val texHeight = texture.getTextureHeight()
        // Convert to texture coordinates
        source.left /= texWidth
        source.right /= texWidth
        source.top /= texHeight
        source.bottom /= texHeight

        // Clip if the rendering range is beyond the bound of the texture.
        val xBound = width.toFloat() / texWidth
        if (source.right > xBound) {
            target.right = target.left + target.width() * (xBound - source.left) / source.width()
            source.right = xBound
        }
        val yBound = height.toFloat() / texHeight
        if (source.bottom > yBound) {
            target.bottom = target.top + target.height() * (yBound - source.top) / source.height()
            source.bottom = yBound
        }
    }

    private fun drawTextureRect(texture: BasicTexture, textureMatrix: FloatArray, target: RectF) {
        val params = prepareTexture(texture)
        setPosition(params, OFFSET_FILL_RECT)
        GLES20.glUniformMatrix4fv(params[INDEX_TEXTURE_MATRIX].handle, 1, false, textureMatrix, 0)
        checkGlError()
        if (texture.isFlippedVertically()) {
            save(GLCanvas.SAVE_FLAG_MATRIX)
            translate(0f, target.centerY())
            scale(1f, -1f, 1f)
            translate(0f, -target.centerY())
        }
        draw(
            params, GLES20.GL_TRIANGLE_STRIP, COUNT_FILL_VERTEX, target.left, target.top,
            target.width(), target.height()
        )
        if (texture.isFlippedVertically()) {
            restore()
        }
        mCountTextureRect++
    }

    private fun prepareTexture(texture: BasicTexture): Array<ShaderParameter> {
        val params: Array<ShaderParameter>
        val program: Int
        if (texture.getTarget() == GLES20.GL_TEXTURE_2D) {
            params = mTextureParameters
            program = mTextureProgram
        } else {
            params = mOesTextureParameters
            program = mOesTextureProgram
        }
        prepareTexture(texture, program, params)
        return params
    }

    private fun prepareTexture(texture: BasicTexture, program: Int, params: Array<ShaderParameter>) {
        GLES20.glUseProgram(program)
        checkGlError()
        enableBlending(!texture.isOpaque() || getAlpha() < OPAQUE_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        checkGlError()
        texture.onBind(this)
        GLES20.glBindTexture(texture.getTarget(), texture.getId())
        checkGlError()
        GLES20.glUniform1i(params[INDEX_TEXTURE_SAMPLER].handle, 0)
        checkGlError()
        GLES20.glUniform1f(params[INDEX_ALPHA].handle, getAlpha())
        checkGlError()
    }

    override fun drawMesh(
        texture: BasicTexture,
        x: Int,
        y: Int,
        xyBuffer: Int,
        uvBuffer: Int,
        indexBuffer: Int,
        indexCount: Int,
    ) {
        prepareTexture(texture, mMeshProgram, mMeshParameters)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        checkGlError()

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, xyBuffer)
        checkGlError()
        val positionHandle = mMeshParameters[INDEX_POSITION].handle
        GLES20.glVertexAttribPointer(
            positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
            VERTEX_STRIDE, 0
        )
        checkGlError()

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, uvBuffer)
        checkGlError()
        val texCoordHandle = mMeshParameters[INDEX_TEXTURE_COORD].handle
        GLES20.glVertexAttribPointer(
            texCoordHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT,
            false, VERTEX_STRIDE, 0
        )
        checkGlError()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        checkGlError()

        GLES20.glEnableVertexAttribArray(positionHandle)
        checkGlError()
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        checkGlError()

        setMatrix(mMeshParameters, x.toFloat(), y.toFloat(), 1f, 1f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLE_STRIP, indexCount, GLES20.GL_UNSIGNED_BYTE, 0)
        checkGlError()

        GLES20.glDisableVertexAttribArray(positionHandle)
        checkGlError()
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        checkGlError()
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        checkGlError()
        mCountDrawMesh++
    }

    override fun drawMixed(texture: BasicTexture, toColor: Int, ratio: Float, x: Int, y: Int, w: Int, h: Int) {
        copyTextureCoordinates(texture, mTempSourceRect)
        mTempTargetRect.set(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        drawMixed(texture, toColor, ratio, mTempSourceRect, mTempTargetRect)
    }

    override fun drawMixed(texture: BasicTexture, toColor: Int, ratio: Float, source: RectF, target: RectF) {
        if (target.width() <= 0 || target.height() <= 0) {
            return
        }
        save(GLCanvas.SAVE_FLAG_ALPHA)

        val currentAlpha = getAlpha()
        val cappedRatio = min(1f, max(0f, ratio))

        val textureAlpha = (1f - cappedRatio) * currentAlpha
        setAlpha(textureAlpha)
        drawTexture(texture, source, target)

        if (0 != Color.alpha(toColor)) {
            val colorAlpha = cappedRatio * currentAlpha
            setAlpha(colorAlpha)
            fillRect(target.left, target.top, target.width(), target.height(), toColor)
        }

        restore()
    }

    override fun unloadTexture(texture: BasicTexture): Boolean {
        val unload = texture.isLoaded()
        if (unload) {
            synchronized(mUnboundTextures) {
                mUnboundTextures.add(texture.getId())
            }
        }
        return unload
    }

    override fun deleteBuffer(bufferId: Int) {
        synchronized(mUnboundTextures) {
            mDeleteBuffers.add(bufferId)
        }
    }

    override fun deleteRecycledResources() {
        synchronized(mUnboundTextures) {
            var ids = mUnboundTextures
            if (mUnboundTextures.size() > 0) {
                mGLId.glDeleteTextures(null, ids.size(), ids.getInternalArray(), 0)
                ids.clear()
            }

            ids = mDeleteBuffers
            if (ids.size() > 0) {
                mGLId.glDeleteBuffers(null, ids.size(), ids.getInternalArray(), 0)
                ids.clear()
            }
        }
    }

    override fun dumpStatisticsAndClear() {
        val line = String.format(
            Locale.US, "MESH:%d, TEX_RECT:%d, FILL_RECT:%d, LINE:%d", mCountDrawMesh,
            mCountTextureRect, mCountFillRect, mCountDrawLine
        )
        mCountDrawMesh = 0
        mCountTextureRect = 0
        mCountFillRect = 0
        mCountDrawLine = 0
        Log.d(TAG, line)
    }

    override fun setTextureParameters(texture: BasicTexture) {
        val target = texture.getTarget()
        GLES20.glBindTexture(target, texture.getId())
        checkGlError()
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
    }

    override fun initializeTextureSize(texture: BasicTexture, format: Int, type: Int) {
        val target = texture.getTarget()
        GLES20.glBindTexture(target, texture.getId())
        checkGlError()
        val width = texture.getTextureWidth()
        val height = texture.getTextureHeight()
        GLES20.glTexImage2D(target, 0, format, width, height, 0, format, type, null)
    }

    override fun initializeTexture(texture: BasicTexture, bitmap: Bitmap) {
        val target = texture.getTarget()
        GLES20.glBindTexture(target, texture.getId())
        checkGlError()
        GLUtils.texImage2D(target, 0, bitmap, 0)
    }

    override fun texSubImage2D(
        texture: BasicTexture,
        xOffset: Int,
        yOffset: Int,
        bitmap: Bitmap,
        format: Int,
        type: Int,
    ) {
        val target = texture.getTarget()
        GLES20.glBindTexture(target, texture.getId())
        checkGlError()
        GLUtils.texSubImage2D(target, 0, xOffset, yOffset, bitmap, format, type)
    }

    override fun uploadBuffer(buffer: FloatBuffer): Int {
        return uploadBuffer(buffer, FLOAT_SIZE)
    }

    override fun uploadBuffer(buffer: ByteBuffer): Int {
        return uploadBuffer(buffer, 1)
    }

    private fun uploadBuffer(buffer: Buffer, elementSize: Int): Int {
        mGLId.glGenBuffers(1, mTempIntArray, 0)
        checkGlError()
        val bufferId = mTempIntArray[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferId)
        checkGlError()
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, buffer.capacity() * elementSize, buffer,
            GLES20.GL_STATIC_DRAW
        )
        checkGlError()
        return bufferId
    }

    override fun recoverFromLightCycle() {
        GLES20.glViewport(0, 0, mWidth, mHeight)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        checkGlError()
    }

    override fun getBounds(bounds: Rect, x: Int, y: Int, width: Int, height: Int) {
        Matrix.translateM(mTempMatrix, 0, mMatrices, mCurrentMatrixIndex, x.toFloat(), y.toFloat(), 0f)
        Matrix.scaleM(mTempMatrix, 0, width.toFloat(), height.toFloat(), 1f)
        Matrix.multiplyMV(mTempMatrix, MATRIX_SIZE, mTempMatrix, 0, BOUNDS_COORDINATES, 0)
        Matrix.multiplyMV(mTempMatrix, MATRIX_SIZE + 4, mTempMatrix, 0, BOUNDS_COORDINATES, 4)
        bounds.left = Math.round(mTempMatrix[MATRIX_SIZE])
        bounds.right = Math.round(mTempMatrix[MATRIX_SIZE + 4])
        bounds.top = Math.round(mTempMatrix[MATRIX_SIZE + 1])
        bounds.bottom = Math.round(mTempMatrix[MATRIX_SIZE + 5])
        bounds.sort()
    }

    override fun getGLId(): GLId {
        return mGLId
    }

    companion object {
        // ************** Constants **********************
        private const val FLOAT_SIZE = java.lang.Float.SIZE / java.lang.Byte.SIZE
        private const val OPAQUE_ALPHA = 0.95f

        private const val COORDS_PER_VERTEX = 2
        private const val VERTEX_STRIDE = COORDS_PER_VERTEX * FLOAT_SIZE

        private const val COUNT_FILL_VERTEX = 4
        private const val COUNT_LINE_VERTEX = 2
        private const val COUNT_RECT_VERTEX = 4
        private const val COUNT_CIRCLE_VERTEX = 120 // multiple of 4
        private const val OFFSET_FILL_RECT = 0
        private const val OFFSET_DRAW_LINE = OFFSET_FILL_RECT + COUNT_FILL_VERTEX
        private const val OFFSET_DRAW_RECT = OFFSET_DRAW_LINE + COUNT_LINE_VERTEX
        private const val OFFSET_FILL_CIRCLE = OFFSET_DRAW_RECT + COUNT_RECT_VERTEX
        private const val OFFSET_DRAW_CIRCLE = OFFSET_FILL_CIRCLE + 1
        private const val OFFSET_LAST = OFFSET_DRAW_CIRCLE + COUNT_CIRCLE_VERTEX + 1

        private val BOX_COORDINATES = FloatArray(OFFSET_LAST * 2)

        init {
            val temp = floatArrayOf(
                0f, 0f, // Fill rectangle
                1f, 0f,
                0f, 1f,
                1f, 1f,
                0f, 0f, // Draw line
                1f, 1f,
                0f, 0f, // Draw rectangle outline
                0f, 1f,
                1f, 1f,
                1f, 0f,
                0.5f, 0.5f, // Fill circle
            )
            System.arraycopy(temp, 0, BOX_COORDINATES, 0, temp.size)

            // Draw circle
            val arrayOffset = OFFSET_DRAW_CIRCLE * 2
            val n = COUNT_CIRCLE_VERTEX / 4
            for (i in 0..n) {
                val value = sin(MathUtils.radians(90f / n * i).toDouble()).toFloat() / 2
                val positive = value + 0.5f
                val negative = -value + 0.5f
                BOX_COORDINATES[arrayOffset + n * 0 + i * 2 + 1] = positive
                BOX_COORDINATES[arrayOffset + n * 2 - i * 2 + 0] = positive
                BOX_COORDINATES[arrayOffset + n * 2 + i * 2 + 0] = negative
                BOX_COORDINATES[arrayOffset + n * 4 - i * 2 + 1] = positive
                BOX_COORDINATES[arrayOffset + n * 4 + i * 2 + 1] = negative
                BOX_COORDINATES[arrayOffset + n * 6 - i * 2 + 0] = negative
                BOX_COORDINATES[arrayOffset + n * 6 + i * 2 + 0] = positive
                BOX_COORDINATES[arrayOffset + n * 8 - i * 2 + 1] = negative
            }
        }

        private val BOUNDS_COORDINATES = floatArrayOf(
            0f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f,
        )

        private const val POSITION_ATTRIBUTE = "aPosition"
        private const val COLOR_UNIFORM = "uColor"
        private const val MATRIX_UNIFORM = "uMatrix"
        private const val TEXTURE_MATRIX_UNIFORM = "uTextureMatrix"
        private const val TEXTURE_SAMPLER_UNIFORM = "uTextureSampler"
        private const val ALPHA_UNIFORM = "uAlpha"
        private const val TEXTURE_COORD_ATTRIBUTE = "aTextureCoordinate"

        private const val DRAW_VERTEX_SHADER = (
            "" +
                "uniform mat4 " + MATRIX_UNIFORM + ";\n" +
                "attribute vec2 " + POSITION_ATTRIBUTE + ";\n" +
                "void main() {\n" +
                "  vec4 pos = vec4(" + POSITION_ATTRIBUTE + ", 0.0, 1.0);\n" +
                "  gl_Position = " + MATRIX_UNIFORM + " * pos;\n" +
                "}\n"
            )

        private const val DRAW_FRAGMENT_SHADER = (
            "" +
                "precision mediump float;\n" +
                "uniform vec4 " + COLOR_UNIFORM + ";\n" +
                "void main() {\n" +
                "  gl_FragColor = " + COLOR_UNIFORM + ";\n" +
                "}\n"
            )

        private const val TEXTURE_VERTEX_SHADER = (
            "" +
                "uniform mat4 " + MATRIX_UNIFORM + ";\n" +
                "uniform mat4 " + TEXTURE_MATRIX_UNIFORM + ";\n" +
                "attribute vec2 " + POSITION_ATTRIBUTE + ";\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "  vec4 pos = vec4(" + POSITION_ATTRIBUTE + ", 0.0, 1.0);\n" +
                "  gl_Position = " + MATRIX_UNIFORM + " * pos;\n" +
                "  vTextureCoord = (" + TEXTURE_MATRIX_UNIFORM + " * pos).xy;\n" +
                "}\n"
            )

        private const val MESH_VERTEX_SHADER = (
            "" +
                "uniform mat4 " + MATRIX_UNIFORM + ";\n" +
                "attribute vec2 " + POSITION_ATTRIBUTE + ";\n" +
                "attribute vec2 " + TEXTURE_COORD_ATTRIBUTE + ";\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "  vec4 pos = vec4(" + POSITION_ATTRIBUTE + ", 0.0, 1.0);\n" +
                "  gl_Position = " + MATRIX_UNIFORM + " * pos;\n" +
                "  vTextureCoord = " + TEXTURE_COORD_ATTRIBUTE + ";\n" +
                "}\n"
            )

        private const val TEXTURE_FRAGMENT_SHADER = (
            "" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform float " + ALPHA_UNIFORM + ";\n" +
                "uniform sampler2D " + TEXTURE_SAMPLER_UNIFORM + ";\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(" + TEXTURE_SAMPLER_UNIFORM + ", vTextureCoord);\n" +
                "  gl_FragColor *= " + ALPHA_UNIFORM + ";\n" +
                "}\n"
            )

        private const val OES_TEXTURE_FRAGMENT_SHADER = (
            "" +
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform float " + ALPHA_UNIFORM + ";\n" +
                "uniform samplerExternalOES " + TEXTURE_SAMPLER_UNIFORM + ";\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(" + TEXTURE_SAMPLER_UNIFORM + ", vTextureCoord);\n" +
                "  gl_FragColor *= " + ALPHA_UNIFORM + ";\n" +
                "}\n"
            )

        private const val INITIAL_RESTORE_STATE_SIZE = 8
        private const val MATRIX_SIZE = 16

        // Handle indices -- common
        private const val INDEX_POSITION = 0
        private const val INDEX_MATRIX = 1

        // Handle indices -- draw
        private const val INDEX_COLOR = 2

        // Handle indices -- texture
        private const val INDEX_TEXTURE_MATRIX = 2
        private const val INDEX_TEXTURE_SAMPLER = 3
        private const val INDEX_ALPHA = 4

        // Handle indices -- mesh
        private const val INDEX_TEXTURE_COORD = 2

        private val mGLId: GLId = GLES20IdImpl()

        @JvmStatic
        fun checkError() {
            checkGlError()
        }
    }
}

private val TAG: String = GLES20Canvas::class.java.simpleName

// Top-level rather than in the companion object: the draw paths call it after
// every GL call, and a top-level function is a plain static call.
internal fun checkGlError() {
    val error = GLES20.glGetError()
    if (error != 0) {
        val t = Throwable()
        Log.e(TAG, "GL error: $error", t)
    }
}
