/*
 * Copyright (C) 2015 Hippo Seven
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
    "ComplexCondition",
    "LoopWithTooManyJumpStatements",
    "UseCheckOrError",
)

package com.lanraragi.framework.lib.glview.image

import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import androidx.annotation.IntDef
import com.lanraragi.framework.lib.glview.annotation.RenderThread
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.glrenderer.NativeTexture
import com.lanraragi.framework.lib.glview.glrenderer.Texture
import com.lanraragi.framework.lib.glview.view.GLRoot
import com.lanraragi.reader.util.TraceSections
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Call [ImageWrapper.obtain] first
 */
open class ImageTexture(private val image: ImageWrapper) : Texture, Animatable {

    private val tiles: Array<Tile> // Can be modified in different threads.
    private val imageWidth: Int

    // Should be protected by "synchronized."
    private val imageHeight: Int
    private val opaque: Boolean
    private val srcRect = RectF()
    private val destRect = RectF()
    private val running = AtomicBoolean()
    private val requestAnimation = AtomicBoolean()
    private val frameDirty = AtomicBoolean()
    private val needRelease = AtomicBoolean()
    private val released = AtomicBoolean()

    // Viewport-relative tile regions in texture coordinates (render thread only).
    // Until the view reports where the page sits, every tile counts as near.
    private val screenRegion = RectF()
    private val uploadRegion = RectF()
    private val keepRegion = RectF()
    private var hasRegions = false

    @Volatile
    private var recycled = false
    private var uploader: Uploader? = null
    private var imageBusy = false
    private var animateRunnable: Runnable? = null

    private var callbackRef: WeakReference<Callback>? = null

    // After every field above: the Java constructor ran after all field initializers.
    init {
        val width = image.width
        imageWidth = width
        val height = image.height
        imageHeight = height
        val opaque = image.isOpaque
        this.opaque = opaque
        val list = ArrayList<Tile>()

        var x = 0
        while (x < width) {
            var y = 0
            while (y < height) {
                val w = min(LARGE_CONTENT_SIZE, width - x)
                val h = min(LARGE_CONTENT_SIZE, height - y)

                if (w <= SMALL_CONTENT_SIZE) {
                    val tile = obtainSmallTile()
                    tile.offsetX = x
                    tile.offsetY = y
                    tile.image = image
                    tile.setSize(TILE_SMALL, w, min(SMALL_CONTENT_SIZE, h))
                    tile.setOpaque(opaque)
                    list.add(tile)

                    val nextHeight = h - SMALL_CONTENT_SIZE
                    if (nextHeight > 0) {
                        val nextTile = obtainSmallTile()
                        nextTile.offsetX = x
                        nextTile.offsetY = y + SMALL_CONTENT_SIZE
                        nextTile.image = image
                        nextTile.setSize(TILE_SMALL, w, nextHeight)
                        nextTile.setOpaque(opaque)
                        list.add(nextTile)
                    }
                } else if (h <= SMALL_CONTENT_SIZE) {
                    val tile = obtainSmallTile()
                    tile.offsetX = x
                    tile.offsetY = y
                    tile.image = image
                    tile.setSize(TILE_SMALL, min(SMALL_CONTENT_SIZE, w), h)
                    tile.setOpaque(opaque)
                    list.add(tile)

                    val nextWidth = w - SMALL_CONTENT_SIZE
                    if (nextWidth > 0) {
                        val nextTile = obtainSmallTile()
                        nextTile.offsetX = x + SMALL_CONTENT_SIZE
                        nextTile.offsetY = y
                        nextTile.image = image
                        nextTile.setSize(TILE_SMALL, nextWidth, h)
                        nextTile.setOpaque(opaque)
                        list.add(nextTile)
                    }
                } else {
                    val tile = obtainLargeTile()
                    tile.offsetX = x
                    tile.offsetY = y
                    tile.image = image
                    tile.setSize(TILE_LARGE, w, h)
                    tile.setOpaque(opaque)
                    list.add(tile)
                }
                y += LARGE_CONTENT_SIZE
            }
            x += LARGE_CONTENT_SIZE
        }

        tiles = list.toTypedArray()
    }

    // We want to draw the "source" on the "target".
    // This method is to find the "output" rectangle which is
    // the corresponding area of the "src".
    //                                   (x,y)  target
    // (x0,y0)  source                     +---------------+
    //    +----------+                     |               |
    //    | src      |                     | output        |
    //    | +--+     |    linear map       | +----+        |
    //    | +--+     |    ---------->      | |    |        |
    //    |          | by (scaleX, scaleY) | +----+        |
    //    +----------+                     |               |
    //      Texture                        +---------------+
    //                                          Canvas
    // A member, not a companion function: it runs for every tile drawn.
    private fun mapRect(
        output: RectF,
        src: RectF,
        x0: Float,
        y0: Float,
        x: Float,
        y: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        output.set(
            x + (src.left - x0) * scaleX,
            y + (src.top - y0) * scaleY,
            x + (src.right - x0) * scaleX,
            y + (src.bottom - y0) * scaleY
        )
    }

    var callback: Callback?
        get() = callbackRef?.get()
        set(cb) {
            callbackRef = WeakReference(cb)
        }

    fun invalidateSelf() {
        callback?.invalidateImageTexture(this)
    }

    override fun start() {
        synchronized(image) {
            if (!imageBusy) {
                imageBusy = true
            } else {
                requestAnimation.lazySet(true)
                return
            }
        }

        val end = released.get() || image.isImageRecycled || needRelease.get() ||
            (!image.animated) || running.get()

        synchronized(image) {
            imageBusy = false
        }

        if (end) {
            return
        }

        running.lazySet(true)

        synchronized(image) {
            if (animateRunnable == null) {
                val runnable: Runnable = AnimateRunnable()
                animateRunnable = runnable
                threadExecutor.execute(runnable)
            }
        }
    }

    override fun stop() {
        running.lazySet(false)
        requestAnimation.lazySet(false)
    }

    override fun isRunning(): Boolean {
        return running.get()
    }

    /**
     * Where the screen is relative to this page, in texture coordinates
     * (see [TileRegions.screenInTexture]). Tiles near the screen are
     * uploaded ahead of time, nearest first; tiles far from it give their GL
     * texture back and are uploaded again when they come near
     * (audit 2026-10-13 PERF-02). An empty rect means "unknown": every tile
     * then counts as near, as before this existed.
     */
    @RenderThread
    fun setScreenRegion(screen: RectF) {
        if (recycled) return
        if (screen.isEmpty) {
            hasRegions = false
        } else {
            screenRegion.set(screen)
            TileRegions.band(uploadRegion, screen, TileRegions.UPLOAD_MARGIN_SCREENS)
            TileRegions.band(keepRegion, screen, TileRegions.KEEP_MARGIN_SCREENS)
            hasRegions = true
            synchronized(tiles) {
                for (t in tiles) {
                    if (t.image != null && t.isLoaded && !overlaps(keepRegion, t)) {
                        t.`yield`()
                        t.preloaded = false
                    }
                }
            }
        }
        val uploader = uploader
        uploader?.addTexture(this)
    }

    // A member, not a companion function: it runs for every tile in the loops below.
    private fun overlaps(region: RectF, t: Tile): Boolean {
        return region.intersects(
            t.offsetX.toFloat(), t.offsetY.toFloat(),
            (t.offsetX + t.contentWidth).toFloat(), (t.offsetY + t.contentHeight).toFloat()
        )
    }

    /** The not-yet-preloaded tile nearest the screen within the upload band, or null.  */
    private fun nextTileToUpload(): Tile? {
        if (recycled) return null
        var best: Tile? = null
        var bestGap = Float.MAX_VALUE
        for (t in tiles) {
            if (t.image == null || t.preloaded) continue
            if (!hasRegions) return t
            if (!overlaps(uploadRegion, t)) continue
            val gap = TileRegions.gap(
                screenRegion, t.offsetX.toFloat(), t.offsetY.toFloat(),
                (t.offsetX + t.contentWidth).toFloat(), (t.offsetY + t.contentHeight).toFloat()
            )
            if (gap < bestGap) {
                best = t
                bestGap = gap
            }
        }
        return best
    }

    private fun uploadNextTile(canvas: GLCanvas): Boolean {
        // Perf trace section (see com.lanraragi.reader.util.TraceSections).
        Trace.beginSection(TraceSections.READER_UPLOAD)
        try {
            return uploadNextTileTraced(canvas)
        } finally {
            Trace.endSection()
        }
    }

    private fun uploadNextTileTraced(canvas: GLCanvas): Boolean {
        synchronized(tiles) {
            val next = nextTileToUpload() ?: return true
            // One attempt per tile: a failed upload is retried by the draw-time
            // bind, and retrying here would spin the idle uploader every frame.
            next.preloaded = true
            val hasBeenLoad = next.isLoaded
            next.updateContent(canvas)

            // It will take some time for a texture to be drawn for the first
            // time. When scrolling, we need to draw several tiles on the screen
            // at the same time. It may cause a UI jank even these textures has
            // been uploaded.
            if (!hasBeenLoad && next.isLoaded) next.draw(canvas, 0, 0)
            return nextTileToUpload() == null
        }
    }

    override fun getWidth(): Int {
        return imageWidth
    }

    override fun getHeight(): Int {
        return imageHeight
    }

    @RenderThread
    private fun syncFrame() {
        if (frameDirty.getAndSet(false)) {
            // invalid tiles
            for (tile in tiles) {
                tile.invalidateContent()
            }
        }
    }

    override fun draw(canvas: GLCanvas, x: Int, y: Int) {
        draw(canvas, x, y, imageWidth, imageHeight)
    }

    // Draws the texture on to the specified rectangle.
    override fun draw(canvas: GLCanvas, x: Int, y: Int, w: Int, h: Int) {
        val src = srcRect
        val dest = destRect
        val scaleX = w.toFloat() / imageWidth
        val scaleY = h.toFloat() / imageHeight

        syncFrame()
        for (t in tiles) {
            src.set(0f, 0f, t.contentWidth.toFloat(), t.contentHeight.toFloat())
            src.offset(t.offsetX.toFloat(), t.offsetY.toFloat())
            mapRect(dest, src, 0f, 0f, x.toFloat(), y.toFloat(), scaleX, scaleY)
            src.offset((t.borderSize - t.offsetX).toFloat(), (t.borderSize - t.offsetY).toFloat())
            canvas.drawTexture(t, src, dest)
        }
    }

    // Draws a sub region of this texture on to the specified rectangle.
    override fun draw(canvas: GLCanvas, source: RectF, target: RectF) {
        val src = srcRect
        val dest = destRect
        val x0 = source.left
        val y0 = source.top
        val x = target.left
        val y = target.top
        val scaleX = target.width() / source.width()
        val scaleY = target.height() / source.height()

        syncFrame()
        for (t in tiles) {
            src.set(0f, 0f, t.contentWidth.toFloat(), t.contentHeight.toFloat())
            src.offset(t.offsetX.toFloat(), t.offsetY.toFloat())
            if (!src.intersect(source)) {
                continue
            }
            mapRect(dest, src, x0, y0, x, y, scaleX, scaleY)
            src.offset((t.borderSize - t.offsetX).toFloat(), (t.borderSize - t.offsetY).toFloat())
            canvas.drawTexture(t, src, dest)
        }
    }

    // Draws a mixed color of this texture and a specified color onto the
    // a rectangle. The used color is: from * (1 - ratio) + to * ratio.
    fun drawMixed(
        canvas: GLCanvas,
        color: Int,
        ratio: Float,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val src = srcRect
        val dest = destRect
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        syncFrame()
        for (t in tiles) {
            src.set(0f, 0f, t.contentWidth.toFloat(), t.contentHeight.toFloat())
            src.offset(t.offsetX.toFloat(), t.offsetY.toFloat())
            mapRect(dest, src, 0f, 0f, x.toFloat(), y.toFloat(), scaleX, scaleY)
            src.offset((t.borderSize - t.offsetX).toFloat(), (t.borderSize - t.offsetY).toFloat())
            canvas.drawMixed(t, color, ratio, src, dest)
        }
    }

    fun drawMixed(canvas: GLCanvas, color: Int, ratio: Float, source: RectF, target: RectF) {
        val src = srcRect
        val dest = destRect
        val x0 = source.left
        val y0 = source.top
        val x = target.left
        val y = target.top
        val scaleX = target.width() / source.width()
        val scaleY = target.height() / source.height()

        syncFrame()
        for (t in tiles) {
            src.set(0f, 0f, t.contentWidth.toFloat(), t.contentHeight.toFloat())
            src.offset(t.offsetX.toFloat(), t.offsetY.toFloat())
            if (!src.intersect(source)) {
                continue
            }
            mapRect(dest, src, x0, y0, x, y, scaleX, scaleY)
            src.offset((t.borderSize - t.offsetX).toFloat(), (t.borderSize - t.offsetY).toFloat())
            canvas.drawMixed(t, color, ratio, src, dest)
        }
    }

    override fun isOpaque(): Boolean {
        return opaque
    }

    /** Nothing left to upload ahead of time for the current screen position.  */
    val isReady: Boolean
        get() {
            synchronized(tiles) {
                return nextTileToUpload() == null
            }
        }

    fun recycle() {
        recycled = true
        running.lazySet(false)

        for (tile in tiles) {
            tile.free()
        }

        val releaseNow = synchronized(image) {
            if (!imageBusy) {
                imageBusy = true
                true
            } else {
                needRelease.lazySet(true)
                false
            }
        }

        if (releaseNow) {
            if (!released.get()) {
                image.release()
                released.lazySet(true)
            }
            synchronized(image) {
                imageBusy = false
            }
        }
    }

    @IntDef(TILE_SMALL, TILE_LARGE)
    @Retention(AnnotationRetention.SOURCE)
    private annotation class TileType

    interface Callback {
        fun invalidateImageTexture(who: ImageTexture)
    }

    class Uploader(private val glRoot: GLRoot) : GLRoot.OnGLIdleListener {
        private val textures = ArrayDeque<ImageTexture>(INIT_CAPACITY)

        private var isQueued = false

        @Synchronized
        fun clear() {
            textures.clear()
        }

        @Synchronized
        fun addTexture(t: ImageTexture) {
            // Remembered so a later screen move can queue the texture again.
            t.uploader = this
            if (t.isReady || textures.contains(t)) return
            textures.addLast(t)

            if (isQueued) return
            isQueued = true
            glRoot.addOnGLIdleListener(this)
        }

        override fun onGLIdle(canvas: GLCanvas?, renderRequested: Boolean): Boolean {
            // GLRootView only runs idle listeners once its canvas exists.
            val glCanvas = checkNotNull(canvas)
            val deque = textures
            synchronized(this) {
                var now = SystemClock.uptimeMillis()
                val dueTime = now + UPLOAD_TILE_LIMIT
                while (now < dueTime && !deque.isEmpty()) {
                    val t = deque.peekFirst()
                    if (t.uploadNextTile(glCanvas)) {
                        deque.removeFirst()
                        glRoot.requestRender()
                    }
                    now = SystemClock.uptimeMillis()
                }
                isQueued = !textures.isEmpty()

                // return true to keep this listener in the queue
                return isQueued
            }
        }
    }

    private class Tile : NativeTexture() {

        @JvmField
        var offsetX = 0

        @JvmField
        var offsetY = 0

        @JvmField
        var image: ImageWrapper? = null

        @JvmField
        var contentWidth = 0

        @JvmField
        var contentHeight = 0

        @JvmField
        var borderSize = 0

        // The idle uploader has handled this tile since it was assigned or yielded.
        @JvmField
        var preloaded = false

        @TileType
        private var tileType = 0

        private fun freeTile(pool: BoundedFreeList<Tile>) {
            invalidate()
            if (!pool.offer(this)) {
                // Pool full: give the texture back instead of keeping it.
                recycle()
            }
        }

        fun setSize(@TileType tileType: Int, width: Int, height: Int) {
            this.tileType = tileType
            val tileSize: Int
            if (tileType == TILE_SMALL) {
                borderSize = SMALL_BORDER_SIZE
                tileSize = SMALL_TILE_SIZE
            } else if (tileType == TILE_LARGE) {
                borderSize = LARGE_BORDER_SIZE
                tileSize = LARGE_TILE_SIZE
            } else {
                throw IllegalStateException("Not support tile type: $tileType")
            }
            contentWidth = width
            contentHeight = height

            mWidth = width + 2 * borderSize
            mHeight = height + 2 * borderSize
            mTextureWidth = tileSize
            mTextureHeight = tileSize
        }

        override fun texImage(init: Boolean) {
            // Capture to local to avoid race with invalidate() setting image = null
            val localImage = image
            if (localImage != null && !localImage.isRecycled) {
                val w: Int
                val h: Int
                if (init) {
                    w = mTextureWidth
                    h = mTextureHeight
                } else {
                    w = mWidth
                    h = mHeight
                }
                localImage.texImage(init, offsetX - borderSize, offsetY - borderSize, w, h)
            }
        }

        private fun invalidate() {
            invalidateContent()
            image = null
            preloaded = false
        }

        fun free() {
            when (tileType) {
                TILE_SMALL -> freeTile(smallFreeTiles)
                TILE_LARGE -> freeTile(largeFreeTiles)
                else -> throw IllegalStateException("Not support tile type: $tileType")
            }
        }
    }

    private inner class AnimateRunnable : Runnable {

        fun doRun() {
            var lastTime = System.nanoTime()
            var lastDelay = -1L

            synchronized(image) {
                // Check released, image busy, Need release
                if (released.get() || image.isImageRecycled || imageBusy || needRelease.get()) {
                    animateRunnable = null
                    return
                }
                // Obtain image
                imageBusy = true
            }

            synchronized(image) {
                // Release image
                imageBusy = false
                // Check need release, frameCount <= 1
                if (needRelease.get() || !image.animated) {
                    animateRunnable = null
                    return
                }
            }

            if (requestAnimation.get()) {
                running.lazySet(true)
            }

            while (true) {
                // Obtain
                synchronized(image) {
                    // Check released, image busy, Need release, not running
                    if (released.get() || image.isImageRecycled || imageBusy || needRelease.get() ||
                        !running.get()
                    ) {
                        animateRunnable = null
                        return
                    }
                    // Obtain image
                    imageBusy = true
                }

                image.start()
                var delay = image.delay.toLong()
                val time = System.nanoTime()
                if (-1L != lastDelay) {
                    delay -= (time - lastTime) / 1000000 - lastDelay
                }
                lastTime = time
                lastDelay = delay
                image.advanceFrame()
                frameDirty.lazySet(true)
                invalidateSelf()

                synchronized(image) {
                    // Release image
                    imageBusy = false
                }

                if (delay > 0) {
                    try {
                        Thread.sleep(delay)
                    } catch (ignored: InterruptedException) {
                        // Ignore
                    }
                }
            }
        }

        override fun run() {
            doRun()

            while (needRelease.get()) {
                // Obtain
                synchronized(image) {
                    // Check released
                    if (released.get() || image.isImageRecycled) {
                        break
                    }
                    // Check image busy
                    if (imageBusy) {
                        // Image is busy, means it is recycling
                        break
                    }
                    // Obtain image
                    imageBusy = true
                }

                if (!released.get()) {
                    image.release()
                    released.lazySet(true)
                }

                synchronized(image) {
                    // Release image
                    imageBusy = false
                }
            }
        }
    }

    companion object {
        private const val TILE_SMALL = 0
        private const val TILE_LARGE = 1
        private const val SMALL_CONTENT_SIZE = 254
        private const val SMALL_BORDER_SIZE = 1
        private const val SMALL_TILE_SIZE = SMALL_CONTENT_SIZE + 2 * SMALL_BORDER_SIZE
        private const val LARGE_CONTENT_SIZE = SMALL_CONTENT_SIZE * 2
        private const val LARGE_BORDER_SIZE = SMALL_BORDER_SIZE * 2
        private const val LARGE_TILE_SIZE = LARGE_CONTENT_SIZE + 2 * LARGE_BORDER_SIZE
        private const val INIT_CAPACITY = 8

        // We are targeting at 60fps, so we have 16ms for each frame.
        // In this 16ms, we use about 4~8 ms to upload tiles.
        private const val UPLOAD_TILE_LIMIT: Long = 4 // ms
        private val threadExecutor: Executor

        // Freed tiles keep their GL texture for reuse. The pools are capped (about
        // 8 MB small + 16 MB large of RGBA texture) so the tiles of one tall page do
        // not stay in VRAM for the rest of the session, and trimFreeTiles() empties
        // them on memory pressure and reader exit (audit 2026-10-13 PERF-02).
        private const val MAX_FREE_SMALL_TILES = 32
        private const val MAX_FREE_LARGE_TILES = 16
        private val smallFreeTiles = BoundedFreeList<Tile>(MAX_FREE_SMALL_TILES)
        private val largeFreeTiles = BoundedFreeList<Tile>(MAX_FREE_LARGE_TILES)

        init {
            // Up to 32 background-priority animation threads; idle ones exit after 10 s.
            val threadId = AtomicInteger()
            val executor = ThreadPoolExecutor(
                32, 32, 10, TimeUnit.SECONDS,
                LinkedBlockingQueue()
            ) { r ->
                Thread(
                    {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        r.run()
                    },
                    "ImageTexture\$AnimateTask-" + threadId.getAndIncrement()
                )
            }
            executor.allowCoreThreadTimeOut(true)
            threadExecutor = executor
        }

        private fun obtainSmallTile(): Tile {
            val result = smallFreeTiles.poll()
            return result ?: Tile()
        }

        private fun obtainLargeTile(): Tile {
            val result = largeFreeTiles.poll()
            return result ?: Tile()
        }

        /**
         * Release the GL textures of every pooled free tile. Safe on any thread:
         * the texture ids are queued on the canvas and deleted on the GL thread.
         */
        @JvmStatic
        fun trimFreeTiles() {
            for (tile in smallFreeTiles.drain()) {
                tile.recycle()
            }
            for (tile in largeFreeTiles.drain()) {
                tile.recycle()
            }
        }
    }
}
