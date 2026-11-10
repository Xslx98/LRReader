package com.lanraragi.framework.lib.glgallery

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.lanraragi.framework.lib.glview.glrenderer.BasicTexture
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.glrenderer.GLId
import com.lanraragi.framework.lib.glview.glrenderer.GLPaint
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Records nothing: lets a JVM test run GalleryView.render for its gesture,
 * fill and layout code without GL.
 */
@Suppress("TooManyFunctions", "EmptyFunctionBlock")
internal class NoOpCanvas : GLCanvas {
    override fun getGLId(): GLId = throw UnsupportedOperationException()
    override fun setSize(width: Int, height: Int) {}
    override fun clearBuffer() {}
    override fun clearBuffer(argb: FloatArray) {}
    override var alpha: Float = 1f
    override fun multiplyAlpha(alpha: Float) {}
    override fun translate(x: Float, y: Float, z: Float) {}
    override fun translate(x: Float, y: Float) {}
    override fun scale(sx: Float, sy: Float, sz: Float) {}
    override fun rotate(angle: Float, x: Float, y: Float, z: Float) {}
    override fun multiplyMatrix(matrix: FloatArray, offset: Int) {}
    override fun save() {}
    override fun save(saveFlags: Int) {}
    override fun restore() {}
    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, paint: GLPaint) {}
    override fun drawRect(x: Float, y: Float, width: Float, height: Float, paint: GLPaint) {}
    override fun drawOval(cx: Float, cy: Float, radiusX: Float, radiusY: Float, paint: GLPaint) {}
    override fun drawArc(cx: Float, cy: Float, radiusX: Float, radiusY: Float, sweepAngle: Float, paint: GLPaint) {}
    override fun fillRect(x: Float, y: Float, width: Float, height: Float, color: Int) {}
    override fun fillOval(cx: Float, cy: Float, radiusX: Float, radiusY: Float, color: Int) {}
    override fun fillSector(cx: Float, cy: Float, radiusX: Float, radiusY: Float, sweepAngle: Float, color: Int) {}
    override fun drawTexture(texture: BasicTexture, x: Int, y: Int, width: Int, height: Int) {}
    override fun drawMesh(
        texture: BasicTexture, x: Int, y: Int, xyBuffer: Int, uvBuffer: Int, indexBuffer: Int, indexCount: Int,
    ) {}
    override fun drawTexture(texture: BasicTexture, source: RectF, target: RectF) {}
    override fun drawTexture(texture: BasicTexture, textureTransform: FloatArray, x: Int, y: Int, w: Int, h: Int) {}
    override fun drawMixed(texture: BasicTexture, toColor: Int, ratio: Float, x: Int, y: Int, w: Int, h: Int) {}
    override fun drawMixed(texture: BasicTexture, toColor: Int, ratio: Float, source: RectF, target: RectF) {}
    override fun unloadTexture(texture: BasicTexture): Boolean = false
    override fun deleteBuffer(bufferId: Int) {}
    override fun deleteRecycledResources() {}
    override fun dumpStatisticsAndClear() {}
    override fun setTextureParameters(texture: BasicTexture) {}
    override fun initializeTextureSize(texture: BasicTexture, format: Int, type: Int) {}
    override fun initializeTexture(texture: BasicTexture, bitmap: Bitmap) {}
    override fun texSubImage2D(texture: BasicTexture, xOffset: Int, yOffset: Int, bitmap: Bitmap, format: Int, type: Int) {}
    override fun uploadBuffer(buffer: FloatBuffer): Int = 0
    override fun uploadBuffer(buffer: ByteBuffer): Int = 0
    override fun recoverFromLightCycle() {}
    override fun getBounds(bounds: Rect, x: Int, y: Int, width: Int, height: Int) {}
}
