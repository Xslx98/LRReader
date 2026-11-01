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

@Suppress("LargeClass") // the whole GLCanvas over one GL state; splitting it would put the draw path behind another object
class GLES20Canvas : GLCanvas {

    // Keep track of restore state
    private var matrices = FloatArray(INITIAL_RESTORE_STATE_SIZE * MATRIX_SIZE)
    private var alphas = FloatArray(INITIAL_RESTORE_STATE_SIZE)
    private val saveFlagStack = IntList()

    private var currentAlphaIndex = 0
    private var currentMatrixIndex = 0

    // Viewport size
    private var viewportWidth = 0
    private var viewportHeight = 0

    // Projection matrix
    private val projectionMatrix = FloatArray(MATRIX_SIZE)

    // GL programs
    private val drawProgram: Int
    private val textureProgram: Int
    private val oesTextureProgram: Int
    private val meshProgram: Int

    // GL buffer containing BOX_COORDINATES
    private val boxCoordinates: Int

    private abstract class ShaderParameter(protected val name: String) {
        @JvmField
        var handle = 0

        abstract fun loadHandle(program: Int)
    }

    private class UniformShaderParameter(name: String) : ShaderParameter(name) {
        override fun loadHandle(program: Int) {
            handle = GLES20.glGetUniformLocation(program, name)
            checkGlError()
        }
    }

    private class AttributeShaderParameter(name: String) : ShaderParameter(name) {
        override fun loadHandle(program: Int) {
            handle = GLES20.glGetAttribLocation(program, name)
            checkGlError()
        }
    }

    private val drawParameters = arrayOf<ShaderParameter>(
        AttributeShaderParameter(POSITION_ATTRIBUTE), // INDEX_POSITION
        UniformShaderParameter(MATRIX_UNIFORM), // INDEX_MATRIX
        UniformShaderParameter(COLOR_UNIFORM), // INDEX_COLOR
    )
    private val textureParameters = arrayOf<ShaderParameter>(
        AttributeShaderParameter(POSITION_ATTRIBUTE), // INDEX_POSITION
        UniformShaderParameter(MATRIX_UNIFORM), // INDEX_MATRIX
        UniformShaderParameter(TEXTURE_MATRIX_UNIFORM), // INDEX_TEXTURE_MATRIX
        UniformShaderParameter(TEXTURE_SAMPLER_UNIFORM), // INDEX_TEXTURE_SAMPLER
        UniformShaderParameter(ALPHA_UNIFORM), // INDEX_ALPHA
    )
    private val oesTextureParameters = arrayOf<ShaderParameter>(
        AttributeShaderParameter(POSITION_ATTRIBUTE), // INDEX_POSITION
        UniformShaderParameter(MATRIX_UNIFORM), // INDEX_MATRIX
        UniformShaderParameter(TEXTURE_MATRIX_UNIFORM), // INDEX_TEXTURE_MATRIX
        UniformShaderParameter(TEXTURE_SAMPLER_UNIFORM), // INDEX_TEXTURE_SAMPLER
        UniformShaderParameter(ALPHA_UNIFORM), // INDEX_ALPHA
    )
    private val meshParameters = arrayOf<ShaderParameter>(
        AttributeShaderParameter(POSITION_ATTRIBUTE), // INDEX_POSITION
        UniformShaderParameter(MATRIX_UNIFORM), // INDEX_MATRIX
        AttributeShaderParameter(TEXTURE_COORD_ATTRIBUTE), // INDEX_TEXTURE_COORD
        UniformShaderParameter(TEXTURE_SAMPLER_UNIFORM), // INDEX_TEXTURE_SAMPLER
        UniformShaderParameter(ALPHA_UNIFORM), // INDEX_ALPHA
    )

    private val unboundTextures = IntList()
    private val buffersToDelete = IntList()

    // Keep track of statistics for debugging
    private var countDrawMesh = 0
    private var countTextureRect = 0
    private var countFillRect = 0
    private var countDrawLine = 0

    // Temporary variables used within calculations
    private val tempMatrix = FloatArray(32)
    private val tempColor = FloatArray(4)
    private val tempSourceRect = RectF()
    private val tempTargetRect = RectF()
    private val tempTextureMatrix = FloatArray(MATRIX_SIZE)
    private val tempIntArray = IntArray(1)

    // After every field above: the Java constructor ran after all field initializers.
    init {
        Matrix.setIdentityM(tempTextureMatrix, 0)
        Matrix.setIdentityM(matrices, currentMatrixIndex)
        alphas[currentAlphaIndex] = 1f

        val boxBuffer = createBuffer(BOX_COORDINATES)
        boxCoordinates = uploadBuffer(boxBuffer)

        val drawVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, DRAW_VERTEX_SHADER)
        val textureVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, TEXTURE_VERTEX_SHADER)
        val meshVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, MESH_VERTEX_SHADER)
        val drawFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, DRAW_FRAGMENT_SHADER)
        val textureFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, TEXTURE_FRAGMENT_SHADER)
        val oesTextureFragmentShader = loadShader(
            GLES20.GL_FRAGMENT_SHADER,
            OES_TEXTURE_FRAGMENT_SHADER
        )

        drawProgram = assembleProgram(drawVertexShader, drawFragmentShader, drawParameters)
        textureProgram = assembleProgram(
            textureVertexShader, textureFragmentShader,
            textureParameters
        )
        oesTextureProgram = assembleProgram(
            textureVertexShader, oesTextureFragmentShader,
            oesTextureParameters
        )
        meshProgram = assembleProgram(meshVertexShader, textureFragmentShader, meshParameters)
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
            error("Cannot create GL program: " + GLES20.glGetError())
        }
        GLES20.glAttachShader(program, vertexShader)
        checkGlError()
        GLES20.glAttachShader(program, fragmentShader)
        checkGlError()
        GLES20.glLinkProgram(program)
        checkGlError()
        val linkStatus = tempIntArray
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
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
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        checkGlError()
        Matrix.setIdentityM(matrices, currentMatrixIndex)
        Matrix.orthoM(projectionMatrix, 0, 0f, width.toFloat(), 0f, height.toFloat(), -1f, 1f)
        Matrix.translateM(matrices, currentMatrixIndex, 0f, height.toFloat(), 0f)
        Matrix.scaleM(matrices, currentMatrixIndex, 1f, -1f, 1f)
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

    override var alpha: Float
        get() = alphas[currentAlphaIndex]
        set(alpha) {
            alphas[currentAlphaIndex] = alpha
        }

    override fun multiplyAlpha(alpha: Float) {
        this.alpha = this.alpha * alpha
    }

    override fun translate(x: Float, y: Float, z: Float) {
        Matrix.translateM(matrices, currentMatrixIndex, x, y, z)
    }

    // This is a faster version of translate(x, y, z) because
    // (1) we knows z = 0, (2) we inline the Matrix.translateM call,
    // (3) we unroll the loop
    override fun translate(x: Float, y: Float) {
        val index = currentMatrixIndex
        val m = matrices
        m[index + 12] += m[index + 0] * x + m[index + 4] * y
        m[index + 13] += m[index + 1] * x + m[index + 5] * y
        m[index + 14] += m[index + 2] * x + m[index + 6] * y
        m[index + 15] += m[index + 3] * x + m[index + 7] * y
    }

    override fun scale(sx: Float, sy: Float, sz: Float) {
        Matrix.scaleM(matrices, currentMatrixIndex, sx, sy, sz)
    }

    override fun rotate(angle: Float, x: Float, y: Float, z: Float) {
        if (angle == 0f) {
            return
        }
        val temp = tempMatrix
        Matrix.setRotateM(temp, 0, angle, x, y, z)
        val matrix = matrices
        val index = currentMatrixIndex
        Matrix.multiplyMM(temp, MATRIX_SIZE, matrix, index, temp, 0)
        System.arraycopy(temp, MATRIX_SIZE, matrix, index, MATRIX_SIZE)
    }

    override fun multiplyMatrix(matrix: FloatArray, offset: Int) {
        val temp = tempMatrix
        val currentMatrix = matrices
        val index = currentMatrixIndex
        Matrix.multiplyMM(temp, 0, currentMatrix, index, matrix, offset)
        System.arraycopy(temp, 0, currentMatrix, index, 16)
    }

    override fun save() {
        save(GLCanvas.SAVE_FLAG_ALL)
    }

    override fun save(saveFlags: Int) {
        val saveAlpha = (saveFlags and GLCanvas.SAVE_FLAG_ALPHA) == GLCanvas.SAVE_FLAG_ALPHA
        if (saveAlpha) {
            val currentAlpha = alpha
            currentAlphaIndex++
            if (alphas.size <= currentAlphaIndex) {
                alphas = alphas.copyOf(alphas.size * 2)
            }
            alphas[currentAlphaIndex] = currentAlpha
        }
        val saveMatrix = (saveFlags and GLCanvas.SAVE_FLAG_MATRIX) == GLCanvas.SAVE_FLAG_MATRIX
        if (saveMatrix) {
            val currentIndex = currentMatrixIndex
            currentMatrixIndex += MATRIX_SIZE
            if (matrices.size <= currentMatrixIndex) {
                matrices = matrices.copyOf(matrices.size * 2)
            }
            System.arraycopy(matrices, currentIndex, matrices, currentMatrixIndex, MATRIX_SIZE)
        }
        saveFlagStack.add(saveFlags)
    }

    override fun restore() {
        val restoreFlags = saveFlagStack.removeAt(saveFlagStack.size() - 1)
        val restoreAlpha = (restoreFlags and GLCanvas.SAVE_FLAG_ALPHA) == GLCanvas.SAVE_FLAG_ALPHA
        if (restoreAlpha) {
            currentAlphaIndex--
        }
        val restoreMatrix = (restoreFlags and GLCanvas.SAVE_FLAG_MATRIX) == GLCanvas.SAVE_FLAG_MATRIX
        if (restoreMatrix) {
            currentMatrixIndex -= MATRIX_SIZE
        }
    }

    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, paint: GLPaint) {
        prepareDraw(OFFSET_DRAW_LINE, paint.color, paint.lineWidth)
        draw(drawParameters, GLES20.GL_LINE_STRIP, COUNT_LINE_VERTEX, x1, y1, x2 - x1, y2 - y1)
        countDrawLine++
    }

    override fun drawRect(x: Float, y: Float, width: Float, height: Float, paint: GLPaint) {
        prepareDraw(OFFSET_DRAW_RECT, paint.color, paint.lineWidth)
        draw(drawParameters, GLES20.GL_LINE_LOOP, COUNT_RECT_VERTEX, x, y, width, height)
        countDrawLine++
    }

    override fun drawOval(cx: Float, cy: Float, radiusX: Float, radiusY: Float, paint: GLPaint) {
        val halfLineWidth = paint.lineWidth / 2
        fillOval(cx, cy, radiusX + halfLineWidth, radiusY + halfLineWidth, paint.color)
        fillOval(cx, cy, radiusX - halfLineWidth, radiusY - halfLineWidth, paint.backgroundColor)
    }

    override fun drawArc(
        cx: Float,
        cy: Float,
        radiusX: Float,
        radiusY: Float,
        sweepAngle: Float,
        paint: GLPaint,
    ) {
        val halfLineWidth = paint.lineWidth / 2
        fillSector(cx, cy, radiusX + halfLineWidth, radiusY + halfLineWidth, sweepAngle, paint.color)
        fillOval(cx, cy, radiusX - halfLineWidth, radiusY - halfLineWidth, paint.backgroundColor)
    }

    // Each primitive is prepareDraw(offset, color, lineWidth) followed by
    // draw(drawParameters, ...), called directly from the public methods.
    private fun prepareDraw(offset: Int, color: Int, lineWidth: Float) {
        GLES20.glUseProgram(drawProgram)
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
        GLES20.glUniform4fv(drawParameters[INDEX_COLOR].handle, 1, colorArray, 0)
        setPosition(drawParameters, offset)
        checkGlError()
    }

    private fun getColor(color: Int): FloatArray {
        val alpha = ((color ushr 24) and 0xFF) / 255f * this.alpha
        val red = ((color ushr 16) and 0xFF) / 255f * alpha
        val green = ((color ushr 8) and 0xFF) / 255f * alpha
        val blue = (color and 0xFF) / 255f * alpha
        tempColor[0] = red
        tempColor[1] = green
        tempColor[2] = blue
        tempColor[3] = alpha
        return tempColor
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
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, boxCoordinates)
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
        Matrix.translateM(tempMatrix, 0, matrices, currentMatrixIndex, x, y, 0f)
        Matrix.scaleM(tempMatrix, 0, width, height, 1f)
        Matrix.multiplyMM(tempMatrix, MATRIX_SIZE, projectionMatrix, 0, tempMatrix, 0)
        GLES20.glUniformMatrix4fv(params[INDEX_MATRIX].handle, 1, false, tempMatrix, MATRIX_SIZE)
        checkGlError()
    }

    override fun fillRect(x: Float, y: Float, width: Float, height: Float, color: Int) {
        prepareDraw(OFFSET_FILL_RECT, color, 0f)
        draw(drawParameters, GLES20.GL_TRIANGLE_STRIP, COUNT_FILL_VERTEX, x, y, width, height)
        countFillRect++
    }

    override fun fillOval(cx: Float, cy: Float, radiusX: Float, radiusY: Float, color: Int) {
        prepareDraw(OFFSET_FILL_CIRCLE, color, 0f)
        draw(
            drawParameters, GLES20.GL_TRIANGLE_FAN, OFFSET_LAST - OFFSET_FILL_CIRCLE,
            cx - radiusX, cy - radiusY, radiusX * 2, radiusY * 2
        )
        countFillRect++
    }

    override fun fillSector(cx: Float, cy: Float, radiusX: Float, radiusY: Float, sweepAngle: Float, color: Int) {
        val conjugateAngle = abs(360 - MathUtils.positiveModulo(sweepAngle, 360f))
        val conjugateCount = Math.round(COUNT_CIRCLE_VERTEX * conjugateAngle / 360)
        if (conjugateCount == 0) {
            // It is a circle
            fillOval(cx, cy, radiusX, radiusY, color)
        } else if (conjugateCount < COUNT_CIRCLE_VERTEX) {
            prepareDraw(OFFSET_FILL_CIRCLE, color, 0f)
            draw(
                drawParameters, GLES20.GL_TRIANGLE_FAN, OFFSET_LAST - OFFSET_FILL_CIRCLE - conjugateCount,
                cx - radiusX, cy - radiusY, radiusX * 2, radiusY * 2
            )
            countFillRect++
        }
    }

    override fun drawTexture(texture: BasicTexture, x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        copyTextureCoordinates(texture, tempSourceRect)
        tempTargetRect.set(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        convertCoordinate(tempSourceRect, tempTargetRect, texture)
        drawTextureRect(texture, tempSourceRect, tempTargetRect)
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
        tempSourceRect.set(source)
        tempTargetRect.set(target)

        convertCoordinate(tempSourceRect, tempTargetRect, texture)
        drawTextureRect(texture, tempSourceRect, tempTargetRect)
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
        tempTargetRect.set(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        drawTextureRect(texture, textureTransform, tempTargetRect)
    }

    private fun drawTextureRect(texture: BasicTexture, source: RectF, target: RectF) {
        setTextureMatrix(source)
        drawTextureRect(texture, tempTextureMatrix, target)
    }

    private fun setTextureMatrix(source: RectF) {
        tempTextureMatrix[0] = source.width()
        tempTextureMatrix[5] = source.height()
        tempTextureMatrix[12] = source.left
        tempTextureMatrix[13] = source.top
    }

    // This function changes the source coordinate to the texture coordinates.
    // It also clips the source and target coordinates if it is beyond the
    // bound of the texture.
    // A member, not a static: see copyTextureCoordinates.
    private fun convertCoordinate(source: RectF, target: RectF, texture: BasicTexture) {
        val width = texture.getWidth()
        val height = texture.getHeight()
        val texWidth = texture.textureWidth
        val texHeight = texture.textureHeight
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
        draw(
            params, GLES20.GL_TRIANGLE_STRIP, COUNT_FILL_VERTEX, target.left, target.top,
            target.width(), target.height()
        )
        countTextureRect++
    }

    private fun prepareTexture(texture: BasicTexture): Array<ShaderParameter> {
        val params: Array<ShaderParameter>
        val program: Int
        if (texture.getTarget() == GLES20.GL_TEXTURE_2D) {
            params = textureParameters
            program = textureProgram
        } else {
            params = oesTextureParameters
            program = oesTextureProgram
        }
        prepareTexture(texture, program, params)
        return params
    }

    private fun prepareTexture(texture: BasicTexture, program: Int, params: Array<ShaderParameter>) {
        GLES20.glUseProgram(program)
        checkGlError()
        enableBlending(!texture.isOpaque() || alpha < OPAQUE_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        checkGlError()
        texture.onBind(this)
        GLES20.glBindTexture(texture.getTarget(), texture.id)
        checkGlError()
        GLES20.glUniform1i(params[INDEX_TEXTURE_SAMPLER].handle, 0)
        checkGlError()
        GLES20.glUniform1f(params[INDEX_ALPHA].handle, alpha)
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
        prepareTexture(texture, meshProgram, meshParameters)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        checkGlError()

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, xyBuffer)
        checkGlError()
        val positionHandle = meshParameters[INDEX_POSITION].handle
        GLES20.glVertexAttribPointer(
            positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
            VERTEX_STRIDE, 0
        )
        checkGlError()

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, uvBuffer)
        checkGlError()
        val texCoordHandle = meshParameters[INDEX_TEXTURE_COORD].handle
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

        setMatrix(meshParameters, x.toFloat(), y.toFloat(), 1f, 1f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLE_STRIP, indexCount, GLES20.GL_UNSIGNED_BYTE, 0)
        checkGlError()

        GLES20.glDisableVertexAttribArray(positionHandle)
        checkGlError()
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        checkGlError()
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        checkGlError()
        countDrawMesh++
    }

    override fun drawMixed(texture: BasicTexture, toColor: Int, ratio: Float, x: Int, y: Int, w: Int, h: Int) {
        copyTextureCoordinates(texture, tempSourceRect)
        tempTargetRect.set(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        drawMixed(texture, toColor, ratio, tempSourceRect, tempTargetRect)
    }

    override fun drawMixed(texture: BasicTexture, toColor: Int, ratio: Float, source: RectF, target: RectF) {
        if (target.width() <= 0 || target.height() <= 0) {
            return
        }
        save(GLCanvas.SAVE_FLAG_ALPHA)

        val currentAlpha = alpha
        val cappedRatio = min(1f, max(0f, ratio))

        val textureAlpha = (1f - cappedRatio) * currentAlpha
        alpha = textureAlpha
        drawTexture(texture, source, target)

        if (0 != Color.alpha(toColor)) {
            val colorAlpha = cappedRatio * currentAlpha
            alpha = colorAlpha
            fillRect(target.left, target.top, target.width(), target.height(), toColor)
        }

        restore()
    }

    override fun unloadTexture(texture: BasicTexture): Boolean {
        val unload = texture.isLoaded
        if (unload) {
            synchronized(unboundTextures) {
                unboundTextures.add(texture.id)
            }
        }
        return unload
    }

    override fun deleteBuffer(bufferId: Int) {
        synchronized(unboundTextures) {
            buffersToDelete.add(bufferId)
        }
    }

    override fun deleteRecycledResources() {
        synchronized(unboundTextures) {
            var ids = unboundTextures
            if (unboundTextures.size() > 0) {
                glIdImpl.glDeleteTextures(null, ids.size(), ids.getInternalArray(), 0)
                ids.clear()
            }

            ids = buffersToDelete
            if (ids.size() > 0) {
                glIdImpl.glDeleteBuffers(null, ids.size(), ids.getInternalArray(), 0)
                ids.clear()
            }
        }
    }

    override fun dumpStatisticsAndClear() {
        val line = String.format(
            Locale.US, "MESH:%d, TEX_RECT:%d, FILL_RECT:%d, LINE:%d", countDrawMesh,
            countTextureRect, countFillRect, countDrawLine
        )
        countDrawMesh = 0
        countTextureRect = 0
        countFillRect = 0
        countDrawLine = 0
        Log.d(TAG, line)
    }

    override fun setTextureParameters(texture: BasicTexture) {
        val target = texture.getTarget()
        GLES20.glBindTexture(target, texture.id)
        checkGlError()
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
    }

    override fun initializeTextureSize(texture: BasicTexture, format: Int, type: Int) {
        val target = texture.getTarget()
        GLES20.glBindTexture(target, texture.id)
        checkGlError()
        val width = texture.textureWidth
        val height = texture.textureHeight
        GLES20.glTexImage2D(target, 0, format, width, height, 0, format, type, null)
    }

    override fun initializeTexture(texture: BasicTexture, bitmap: Bitmap) {
        val target = texture.getTarget()
        GLES20.glBindTexture(target, texture.id)
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
        GLES20.glBindTexture(target, texture.id)
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
        glIdImpl.glGenBuffers(1, tempIntArray, 0)
        checkGlError()
        val bufferId = tempIntArray[0]
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
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        checkGlError()
    }

    override fun getBounds(bounds: Rect, x: Int, y: Int, width: Int, height: Int) {
        Matrix.translateM(tempMatrix, 0, matrices, currentMatrixIndex, x.toFloat(), y.toFloat(), 0f)
        Matrix.scaleM(tempMatrix, 0, width.toFloat(), height.toFloat(), 1f)
        Matrix.multiplyMV(tempMatrix, MATRIX_SIZE, tempMatrix, 0, BOUNDS_COORDINATES, 0)
        Matrix.multiplyMV(tempMatrix, MATRIX_SIZE + 4, tempMatrix, 0, BOUNDS_COORDINATES, 4)
        bounds.left = Math.round(tempMatrix[MATRIX_SIZE])
        bounds.right = Math.round(tempMatrix[MATRIX_SIZE + 4])
        bounds.top = Math.round(tempMatrix[MATRIX_SIZE + 1])
        bounds.bottom = Math.round(tempMatrix[MATRIX_SIZE + 5])
        bounds.sort()
    }

    override fun getGLId(): GLId {
        return glIdImpl
    }

    companion object {
        private val glIdImpl: GLId = GLES20IdImpl()
    }
}

private val TAG: String = GLES20Canvas::class.java.simpleName

// Top-level rather than in the companion object: the draw paths call it after
// every GL call, and a top-level function is a plain static call.
internal fun checkGlError() {
    val error = GLES20.glGetError()
    if (error != 0) {
        val message = "GL error: $error"
        Log.e(TAG, message, Throwable(message))
    }
}

// ************** Constants **********************
// Top-level and private: the constants are inlined, and the tables and shader
// sources are only read outside the draw paths.
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

private val BOX_COORDINATES: FloatArray = createBoxCoordinates()

private fun createBoxCoordinates(): FloatArray {
    val boxCoordinates = FloatArray(OFFSET_LAST * 2)
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
    System.arraycopy(temp, 0, boxCoordinates, 0, temp.size)

    // Draw circle
    val arrayOffset = OFFSET_DRAW_CIRCLE * 2
    val n = COUNT_CIRCLE_VERTEX / 4
    for (i in 0..n) {
        val value = sin(MathUtils.radians(90f / n * i).toDouble()).toFloat() / 2
        val positive = value + 0.5f
        val negative = -value + 0.5f
        boxCoordinates[arrayOffset + n * 0 + i * 2 + 1] = positive
        boxCoordinates[arrayOffset + n * 2 - i * 2 + 0] = positive
        boxCoordinates[arrayOffset + n * 2 + i * 2 + 0] = negative
        boxCoordinates[arrayOffset + n * 4 - i * 2 + 1] = positive
        boxCoordinates[arrayOffset + n * 4 + i * 2 + 1] = negative
        boxCoordinates[arrayOffset + n * 6 - i * 2 + 0] = negative
        boxCoordinates[arrayOffset + n * 6 + i * 2 + 0] = positive
        boxCoordinates[arrayOffset + n * 8 - i * 2 + 1] = negative
    }
    return boxCoordinates
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
