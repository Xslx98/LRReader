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
open class ImageTexture(image: ImageWrapper) : Texture, Animatable {

    private val mImage: ImageWrapper = image
    private val mTiles: Array<Tile> // Can be modified in different threads.
    private val mWidth: Int

    // Should be protected by "synchronized."
    private val mHeight: Int
    private val mOpaque: Boolean
    private val mSrcRect = RectF()
    private val mDestRect = RectF()
    private val mRunning = AtomicBoolean()
    private val mRequestAnimation = AtomicBoolean()
    private val mFrameDirty = AtomicBoolean()
    private val mNeedRelease = AtomicBoolean()
    private val mReleased = AtomicBoolean()

    // Viewport-relative tile regions in texture coordinates (render thread only).
    // Until the view reports where the page sits, every tile counts as near.
    private val mScreenRegion = RectF()
    private val mUploadRegion = RectF()
    private val mKeepRegion = RectF()
    private var mHasRegions = false

    @Volatile
    private var mRecycled = false
    private var mUploader: Uploader? = null
    private var mImageBusy = false
    private var mAnimateRunnable: Runnable? = null

    private var mCallback: WeakReference<Callback>? = null

    // After every field above: the Java constructor ran after all field initializers.
    init {
        val width = image.getWidth()
        mWidth = width
        val height = image.getHeight()
        mHeight = height
        val opaque = image.isOpaque()
        mOpaque = opaque
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

        mTiles = list.toTypedArray()
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

    fun getCallback(): Callback? {
        val callback = mCallback
        if (callback != null) {
            return callback.get()
        }
        return null
    }

    fun setCallback(cb: Callback?) {
        mCallback = WeakReference(cb)
    }

    fun invalidateSelf() {
        val callback = getCallback()
        callback?.invalidateImageTexture(this)
    }

    override fun start() {
        synchronized(mImage) {
            if (!mImageBusy) {
                mImageBusy = true
            } else {
                mRequestAnimation.lazySet(true)
                return
            }
        }

        val end = mReleased.get() || mImage.isImageRecycled || mNeedRelease.get() ||
            (!mImage.getAnimated()) || mRunning.get()

        synchronized(mImage) {
            mImageBusy = false
        }

        if (end) {
            return
        }

        mRunning.lazySet(true)

        synchronized(mImage) {
            if (mAnimateRunnable == null) {
                val runnable: Runnable = AnimateRunnable()
                mAnimateRunnable = runnable
                sThreadExecutor.execute(runnable)
            }
        }
    }

    override fun stop() {
        mRunning.lazySet(false)
        mRequestAnimation.lazySet(false)
    }

    override fun isRunning(): Boolean {
        return mRunning.get()
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
        if (mRecycled) return
        if (screen.isEmpty) {
            mHasRegions = false
        } else {
            mScreenRegion.set(screen)
            TileRegions.band(mUploadRegion, screen, TileRegions.UPLOAD_MARGIN_SCREENS)
            TileRegions.band(mKeepRegion, screen, TileRegions.KEEP_MARGIN_SCREENS)
            mHasRegions = true
            synchronized(mTiles) {
                for (t in mTiles) {
                    if (t.image != null && t.isLoaded() && !overlaps(mKeepRegion, t)) {
                        t.`yield`()
                        t.preloaded = false
                    }
                }
            }
        }
        val uploader = mUploader
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
        if (mRecycled) return null
        var best: Tile? = null
        var bestGap = Float.MAX_VALUE
        for (t in mTiles) {
            if (t.image == null || t.preloaded) continue
            if (!mHasRegions) return t
            if (!overlaps(mUploadRegion, t)) continue
            val gap = TileRegions.gap(
                mScreenRegion, t.offsetX.toFloat(), t.offsetY.toFloat(),
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
        synchronized(mTiles) {
            val next = nextTileToUpload() ?: return true
            // One attempt per tile: a failed upload is retried by the draw-time
            // bind, and retrying here would spin the idle uploader every frame.
            next.preloaded = true
            val hasBeenLoad = next.isLoaded()
            next.updateContent(canvas)

            // It will take some time for a texture to be drawn for the first
            // time. When scrolling, we need to draw several tiles on the screen
            // at the same time. It may cause a UI jank even these textures has
            // been uploaded.
            if (!hasBeenLoad && next.isLoaded()) next.draw(canvas, 0, 0)
            return nextTileToUpload() == null
        }
    }

    override fun getWidth(): Int {
        return mWidth
    }

    override fun getHeight(): Int {
        return mHeight
    }

    @RenderThread
    private fun syncFrame() {
        if (mFrameDirty.getAndSet(false)) {
            // invalid tiles
            for (tile in mTiles) {
                tile.invalidateContent()
            }
        }
    }

    override fun draw(canvas: GLCanvas, x: Int, y: Int) {
        draw(canvas, x, y, mWidth, mHeight)
    }

    // Draws the texture on to the specified rectangle.
    override fun draw(canvas: GLCanvas, x: Int, y: Int, w: Int, h: Int) {
        val src = mSrcRect
        val dest = mDestRect
        val scaleX = w.toFloat() / mWidth
        val scaleY = h.toFloat() / mHeight

        syncFrame()
        for (t in mTiles) {
            src.set(0f, 0f, t.contentWidth.toFloat(), t.contentHeight.toFloat())
            src.offset(t.offsetX.toFloat(), t.offsetY.toFloat())
            mapRect(dest, src, 0f, 0f, x.toFloat(), y.toFloat(), scaleX, scaleY)
            src.offset((t.borderSize - t.offsetX).toFloat(), (t.borderSize - t.offsetY).toFloat())
            canvas.drawTexture(t, src, dest)
        }
    }

    // Draws a sub region of this texture on to the specified rectangle.
    override fun draw(canvas: GLCanvas, source: RectF, target: RectF) {
        val src = mSrcRect
        val dest = mDestRect
        val x0 = source.left
        val y0 = source.top
        val x = target.left
        val y = target.top
        val scaleX = target.width() / source.width()
        val scaleY = target.height() / source.height()

        syncFrame()
        for (t in mTiles) {
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
        val src = mSrcRect
        val dest = mDestRect
        val scaleX = width.toFloat() / mWidth
        val scaleY = height.toFloat() / mHeight

        syncFrame()
        for (t in mTiles) {
            src.set(0f, 0f, t.contentWidth.toFloat(), t.contentHeight.toFloat())
            src.offset(t.offsetX.toFloat(), t.offsetY.toFloat())
            mapRect(dest, src, 0f, 0f, x.toFloat(), y.toFloat(), scaleX, scaleY)
            src.offset((t.borderSize - t.offsetX).toFloat(), (t.borderSize - t.offsetY).toFloat())
            canvas.drawMixed(t, color, ratio, src, dest)
        }
    }

    fun drawMixed(canvas: GLCanvas, color: Int, ratio: Float, source: RectF, target: RectF) {
        val src = mSrcRect
        val dest = mDestRect
        val x0 = source.left
        val y0 = source.top
        val x = target.left
        val y = target.top
        val scaleX = target.width() / source.width()
        val scaleY = target.height() / source.height()

        syncFrame()
        for (t in mTiles) {
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
        return mOpaque
    }

    /** Nothing left to upload ahead of time for the current screen position.  */
    fun isReady(): Boolean {
        synchronized(mTiles) {
            return nextTileToUpload() == null
        }
    }

    fun recycle() {
        mRecycled = true
        mRunning.lazySet(false)

        for (mTile in mTiles) {
            mTile.free()
        }

        val releaseNow = synchronized(mImage) {
            if (!mImageBusy) {
                mImageBusy = true
                true
            } else {
                mNeedRelease.lazySet(true)
                false
            }
        }

        if (releaseNow) {
            if (!mReleased.get()) {
                mImage.release()
                mReleased.lazySet(true)
            }
            synchronized(mImage) {
                mImageBusy = false
            }
        }
    }

    @IntDef(TILE_SMALL, TILE_LARGE)
    @Retention(AnnotationRetention.SOURCE)
    private annotation class TileType

    interface Callback {
        fun invalidateImageTexture(who: ImageTexture)
    }

    class Uploader(glRoot: GLRoot) : GLRoot.OnGLIdleListener {
        private val mTextures = ArrayDeque<ImageTexture>(INIT_CAPACITY)

        private val mGlRoot: GLRoot = glRoot
        private var mIsQueued = false

        @Synchronized
        fun clear() {
            mTextures.clear()
        }

        @Synchronized
        fun addTexture(t: ImageTexture) {
            // Remembered so a later screen move can queue the texture again.
            t.mUploader = this
            if (t.isReady() || mTextures.contains(t)) return
            mTextures.addLast(t)

            if (mIsQueued) return
            mIsQueued = true
            mGlRoot.addOnGLIdleListener(this)
        }

        override fun onGLIdle(canvas: GLCanvas?, renderRequested: Boolean): Boolean {
            // GLRootView only runs idle listeners once its canvas exists.
            val glCanvas = checkNotNull(canvas)
            val deque = mTextures
            synchronized(this) {
                var now = SystemClock.uptimeMillis()
                val dueTime = now + UPLOAD_TILE_LIMIT
                while (now < dueTime && !deque.isEmpty()) {
                    val t = deque.peekFirst()
                    if (t.uploadNextTile(glCanvas)) {
                        deque.removeFirst()
                        mGlRoot.requestRender()
                    }
                    now = SystemClock.uptimeMillis()
                }
                mIsQueued = !mTextures.isEmpty()

                // return true to keep this listener in the queue
                return mIsQueued
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
        private var mTileType = 0

        private fun freeTile(pool: BoundedFreeList<Tile>) {
            invalidate()
            if (!pool.offer(this)) {
                // Pool full: give the texture back instead of keeping it.
                recycle()
            }
        }

        fun setSize(@TileType tileType: Int, width: Int, height: Int) {
            mTileType = tileType
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
            if (localImage != null && !localImage.isRecycled()) {
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
            when (mTileType) {
                TILE_SMALL -> freeTile(sSmallFreeTiles)
                TILE_LARGE -> freeTile(sLargeFreeTiles)
                else -> throw IllegalStateException("Not support tile type: $mTileType")
            }
        }
    }

    private inner class AnimateRunnable : Runnable {

        fun doRun() {
            var lastTime = System.nanoTime()
            var lastDelay = -1L

            synchronized(mImage) {
                // Check released, image busy, Need release
                if (mReleased.get() || mImage.isImageRecycled || mImageBusy || mNeedRelease.get()) {
                    mAnimateRunnable = null
                    return
                }
                // Obtain image
                mImageBusy = true
            }

            synchronized(mImage) {
                // Release image
                mImageBusy = false
                // Check need release, frameCount <= 1
                if (mNeedRelease.get() || !mImage.getAnimated()) {
                    mAnimateRunnable = null
                    return
                }
            }

            if (mRequestAnimation.get()) {
                mRunning.lazySet(true)
            }

            while (true) {
                // Obtain
                synchronized(mImage) {
                    // Check released, image busy, Need release, not running
                    if (mReleased.get() || mImage.isImageRecycled || mImageBusy || mNeedRelease.get() ||
                        !mRunning.get()
                    ) {
                        mAnimateRunnable = null
                        return
                    }
                    // Obtain image
                    mImageBusy = true
                }

                mImage.start()
                var delay = mImage.getDelay().toLong()
                val time = System.nanoTime()
                if (-1L != lastDelay) {
                    delay -= (time - lastTime) / 1000000 - lastDelay
                }
                lastTime = time
                lastDelay = delay
                mImage.advanceFrame()
                mFrameDirty.lazySet(true)
                invalidateSelf()

                synchronized(mImage) {
                    // Release image
                    mImageBusy = false
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

            while (mNeedRelease.get()) {
                // Obtain
                synchronized(mImage) {
                    // Check released
                    if (mReleased.get() || mImage.isImageRecycled) {
                        break
                    }
                    // Check image busy
                    if (mImageBusy) {
                        // Image is busy, means it is recycling
                        break
                    }
                    // Obtain image
                    mImageBusy = true
                }

                if (!mReleased.get()) {
                    mImage.release()
                    mReleased.lazySet(true)
                }

                synchronized(mImage) {
                    // Release image
                    mImageBusy = false
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
        private val sThreadExecutor: Executor

        // Freed tiles keep their GL texture for reuse. The pools are capped (about
        // 8 MB small + 16 MB large of RGBA texture) so the tiles of one tall page do
        // not stay in VRAM for the rest of the session, and trimFreeTiles() empties
        // them on memory pressure and reader exit (audit 2026-10-13 PERF-02).
        private const val MAX_FREE_SMALL_TILES = 32
        private const val MAX_FREE_LARGE_TILES = 16
        private val sSmallFreeTiles = BoundedFreeList<Tile>(MAX_FREE_SMALL_TILES)
        private val sLargeFreeTiles = BoundedFreeList<Tile>(MAX_FREE_LARGE_TILES)

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
            sThreadExecutor = executor
        }

        private fun obtainSmallTile(): Tile {
            val result = sSmallFreeTiles.poll()
            return result ?: Tile()
        }

        private fun obtainLargeTile(): Tile {
            val result = sLargeFreeTiles.poll()
            return result ?: Tile()
        }

        /**
         * Release the GL textures of every pooled free tile. Safe on any thread:
         * the texture ids are queued on the canvas and deleted on the GL thread.
         */
        @JvmStatic
        fun trimFreeTiles() {
            for (tile in sSmallFreeTiles.drain()) {
                tile.recycle()
            }
            for (tile in sLargeFreeTiles.drain()) {
                tile.recycle()
            }
        }
    }
}
