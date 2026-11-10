package com.lanraragi.framework.lib.glgallery

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.lanraragi.framework.lib.glview.glrenderer.BasicTexture
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.glrenderer.GLId
import com.lanraragi.framework.lib.glview.glrenderer.GLPaint
import com.lanraragi.framework.lib.glview.util.GalleryUtils
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.util.minAllocatedBytes
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Tier A rule: no per-frame allocation on the touch and render paths. A drag
 * posts a scroll event per touch move from the UI thread and the render thread
 * runs it at its next frame; both halves happen here on the test thread.
 * Before the fix every event boxed six Floats into a vararg array and every
 * frame copied the posted lists (about 140 B per move and 32 B per frame).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryViewGestureAllocationTest {

    private class FakeAdapter : GalleryView.Adapter() {
        override fun onBind(view: GalleryPageView, index: Int) {}
        override fun onUnbind(view: GalleryPageView, index: Int) {}
        override fun getError(): String? = null
        override fun size(): Int = 40
    }

    private val canvas = NoOpCanvas()
    private lateinit var view: GalleryView

    @Before
    fun setUp() {
        // The test thread plays both the UI thread and the GL render thread.
        GalleryUtils.setRenderThread()
        view = GalleryView.Builder(RuntimeEnvironment.getApplication(), FakeAdapter())
            .setLayoutMode(GalleryView.LAYOUT_RIGHT_TO_LEFT)
            .setStartPage(6)
            .build()
        view.onAttachToRootInternal()
        view.measure(
            GLView.MeasureSpec.makeMeasureSpec(1080, GLView.MeasureSpec.EXACTLY),
            GLView.MeasureSpec.makeMeasureSpec(2400, GLView.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 2400)
        view.render(canvas)
    }

    @Test
    fun dragFramesAllocateNothing() {
        view.onDown(540f, 1200f)
        view.render(canvas)
        drag(FRAMES) // warm up
        val bytes = minAllocatedBytes(RUNS) { drag(FRAMES) }

        assertEquals("fewest bytes allocated by $FRAMES drag frames in $RUNS runs", 0L, bytes)
    }

    @Test
    fun pinchFramesAllocateNothing() {
        view.onDown(540f, 1000f)
        view.onPointerDown(540f, 1400f)
        view.onScaleBegin(540f, 1200f)
        view.render(canvas)
        pinch(FRAMES) // warm up
        val bytes = minAllocatedBytes(RUNS) { pinch(FRAMES) }

        assertEquals("fewest bytes allocated by $FRAMES pinch frames in $RUNS runs", 0L, bytes)
    }

    // Small moves back and forth: the page is pulled a little and returned, so
    // the scroll reaches the layout manager without ever turning the page.
    private fun drag(frames: Int) {
        for (i in 0 until frames) {
            val dx = if (i % 2 == 0) 6f else -6f
            view.onScroll(dx, 0f, dx, 0f, 540f - dx, 1200f)
            view.render(canvas)
        }
    }

    private fun pinch(frames: Int) {
        for (i in 0 until frames) {
            view.onScale(540f, 1200f, if (i % 2 == 0) 1.02f else 1f / 1.02f)
            view.render(canvas)
        }
    }

    private companion object {
        const val FRAMES = 200
        const val RUNS = 5
    }

    /** Records nothing: the guard is about the gesture and layout code, not GL. */
    @Suppress("TooManyFunctions", "EmptyFunctionBlock")
    private class NoOpCanvas : GLCanvas {
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
}
