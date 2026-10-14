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

package com.lanraragi.framework.lib.glview.image;

import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import com.lanraragi.framework.lib.glview.annotation.RenderThread;
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas;
import com.lanraragi.framework.lib.glview.glrenderer.NativeTexture;
import com.lanraragi.framework.lib.glview.glrenderer.Texture;
import com.lanraragi.framework.lib.glview.view.GLRoot;
import com.lanraragi.framework.lib.yorozuya.thread.InfiniteThreadExecutor;
import com.lanraragi.reader.util.TraceSections;
import com.lanraragi.framework.lib.yorozuya.thread.PVLock;
import com.lanraragi.framework.lib.yorozuya.thread.PriorityThreadFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class ImageTexture implements Texture, Animatable {

    private static final int TILE_SMALL = 0;
    private static final int TILE_LARGE = 1;
    private static final int SMALL_CONTENT_SIZE = 254;
    private static final int SMALL_BORDER_SIZE = 1;
    private static final int SMALL_TILE_SIZE = SMALL_CONTENT_SIZE + 2 * SMALL_BORDER_SIZE;
    private static final int LARGE_CONTENT_SIZE = SMALL_CONTENT_SIZE * 2;
    private static final int LARGE_BORDER_SIZE = SMALL_BORDER_SIZE * 2;
    private static final int LARGE_TILE_SIZE = LARGE_CONTENT_SIZE + 2 * LARGE_BORDER_SIZE;
    private static final int INIT_CAPACITY = 8;
    // We are targeting at 60fps, so we have 16ms for each frame.
    // In this 16ms, we use about 4~8 ms to upload tiles.
    private static final long UPLOAD_TILE_LIMIT = 4; // ms
    private static final Executor sThreadExecutor;
    private static final PVLock sPVLock;
    // Freed tiles keep their GL texture for reuse. The pools are capped (about
    // 8 MB small + 16 MB large of RGBA texture) so the tiles of one tall page do
    // not stay in VRAM for the rest of the session, and trimFreeTiles() empties
    // them on memory pressure and reader exit (audit 2026-10-13 PERF-02).
    private static final int MAX_FREE_SMALL_TILES = 32;
    private static final int MAX_FREE_LARGE_TILES = 16;
    private static final BoundedFreeList<Tile> sSmallFreeTiles = new BoundedFreeList<>(MAX_FREE_SMALL_TILES);
    private static final BoundedFreeList<Tile> sLargeFreeTiles = new BoundedFreeList<>(MAX_FREE_LARGE_TILES);

    static {
        sThreadExecutor = new InfiniteThreadExecutor(10 * 1000, new LinkedList<Runnable>(),
                new PriorityThreadFactory("ImageTexture$AnimateTask", Process.THREAD_PRIORITY_BACKGROUND));
        sPVLock = new PVLock(3);
    }

    private final ImageWrapper mImage;
    private final Tile[] mTiles;  // Can be modified in different threads.
    private final int mWidth;
    // Should be protected by "synchronized."
    private final int mHeight;
    private final boolean mOpaque;
    private final RectF mSrcRect = new RectF();
    private final RectF mDestRect = new RectF();
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mRequestAnimation = new AtomicBoolean();
    private final AtomicBoolean mFrameDirty = new AtomicBoolean();
    private final AtomicBoolean mNeedRelease = new AtomicBoolean();
    private final AtomicBoolean mReleased = new AtomicBoolean();
    // Viewport-relative tile regions in texture coordinates (render thread only).
    // Until the view reports where the page sits, every tile counts as near.
    private final RectF mScreenRegion = new RectF();
    private final RectF mUploadRegion = new RectF();
    private final RectF mKeepRegion = new RectF();
    private boolean mHasRegions = false;
    private volatile boolean mRecycled = false;
    private Uploader mUploader;
    private boolean mImageBusy = false;
    private Runnable mAnimateRunnable = null;

    private WeakReference<Callback> mCallback;

    /**
     * Call {@link ImageWrapper#obtain()} first
     */
    public ImageTexture(@NonNull ImageWrapper image) {
        mImage = image;
        int width = mWidth = image.getWidth();
        int height = mHeight = image.getHeight();
        boolean opaque = mOpaque = image.isOpaque();
        ArrayList<Tile> list = new ArrayList<>();

        for (int x = 0; x < width; x += LARGE_CONTENT_SIZE) {
            for (int y = 0; y < height; y += LARGE_CONTENT_SIZE) {
                int w = Math.min(LARGE_CONTENT_SIZE, width - x);
                int h = Math.min(LARGE_CONTENT_SIZE, height - y);

                if (w <= SMALL_CONTENT_SIZE) {
                    Tile tile = obtainSmallTile();
                    tile.offsetX = x;
                    tile.offsetY = y;
                    tile.image = image;
                    tile.setSize(TILE_SMALL, w, Math.min(SMALL_CONTENT_SIZE, h));
                    tile.setOpaque(opaque);
                    list.add(tile);

                    int nextHeight = h - SMALL_CONTENT_SIZE;
                    if (nextHeight > 0) {
                        Tile nextTile = obtainSmallTile();
                        nextTile.offsetX = x;
                        nextTile.offsetY = y + SMALL_CONTENT_SIZE;
                        nextTile.image = image;
                        nextTile.setSize(TILE_SMALL, w, nextHeight);
                        nextTile.setOpaque(opaque);
                        list.add(nextTile);
                    }
                } else if (h <= SMALL_CONTENT_SIZE) {
                    Tile tile = obtainSmallTile();
                    tile.offsetX = x;
                    tile.offsetY = y;
                    tile.image = image;
                    tile.setSize(TILE_SMALL, Math.min(SMALL_CONTENT_SIZE, w), h);
                    tile.setOpaque(opaque);
                    list.add(tile);

                    int nextWidth = w - SMALL_CONTENT_SIZE;
                    if (nextWidth > 0) {
                        Tile nextTile = obtainSmallTile();
                        nextTile.offsetX = x + SMALL_CONTENT_SIZE;
                        nextTile.offsetY = y;
                        nextTile.image = image;
                        nextTile.setSize(TILE_SMALL, nextWidth, h);
                        nextTile.setOpaque(opaque);
                        list.add(nextTile);
                    }
                } else {
                    Tile tile = obtainLargeTile();
                    tile.offsetX = x;
                    tile.offsetY = y;
                    tile.image = image;
                    tile.setSize(TILE_LARGE, w, h);
                    tile.setOpaque(opaque);
                    list.add(tile);
                }
            }
        }

        mTiles = list.toArray(new Tile[list.size()]);
    }

    private static Tile obtainSmallTile() {
        Tile result = sSmallFreeTiles.poll();
        return result != null ? result : new Tile();
    }

    private static Tile obtainLargeTile() {
        Tile result = sLargeFreeTiles.poll();
        return result != null ? result : new Tile();
    }

    /**
     * Release the GL textures of every pooled free tile. Safe on any thread:
     * the texture ids are queued on the canvas and deleted on the GL thread.
     */
    public static void trimFreeTiles() {
        for (Tile tile : sSmallFreeTiles.drain()) {
            tile.recycle();
        }
        for (Tile tile : sLargeFreeTiles.drain()) {
            tile.recycle();
        }
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
    private static void mapRect(RectF output,
                                RectF src, float x0, float y0, float x, float y, float scaleX,
                                float scaleY) {
        output.set(x + (src.left - x0) * scaleX,
                y + (src.top - y0) * scaleY,
                x + (src.right - x0) * scaleX,
                y + (src.bottom - y0) * scaleY);
    }

    public Callback getCallback() {
        if (mCallback != null) {
            return mCallback.get();
        }
        return null;
    }

    public final void setCallback(Callback cb) {
        mCallback = new WeakReference<>(cb);
    }

    public void invalidateSelf() {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.invalidateImageTexture(this);
        }
    }

    @Override
    public void start() {
        synchronized (mImage) {
            if (!mImageBusy) {
                mImageBusy = true;
            } else {
                mRequestAnimation.lazySet(true);
                return;
            }
        }

        boolean end = mReleased.get() || mImage.isImageRecycled() || mNeedRelease.get() ||
                (!mImage.getAnimated()) || mRunning.get();

        synchronized (mImage) {
            mImageBusy = false;
        }

        if (end) {
            return;
        }

        mRunning.lazySet(true);

        synchronized (mImage) {
            if (mAnimateRunnable == null) {
                Runnable runnable = new AnimateRunnable();
                mAnimateRunnable = runnable;
                sThreadExecutor.execute(runnable);
            }
        }
    }

    @Override
    public void stop() {
        mRunning.lazySet(false);
        mRequestAnimation.lazySet(false);
    }

    @Override
    public boolean isRunning() {
        return mRunning.get();
    }

    /**
     * Where the screen is relative to this page, in texture coordinates
     * (see {@link TileRegions#screenInTexture}). Tiles near the screen are
     * uploaded ahead of time, nearest first; tiles far from it give their GL
     * texture back and are uploaded again when they come near
     * (audit 2026-10-13 PERF-02). An empty rect means "unknown": every tile
     * then counts as near, as before this existed.
     */
    @RenderThread
    public void setScreenRegion(RectF screen) {
        if (mRecycled) return;
        if (screen.isEmpty()) {
            mHasRegions = false;
        } else {
            mScreenRegion.set(screen);
            TileRegions.band(mUploadRegion, screen, TileRegions.UPLOAD_MARGIN_SCREENS);
            TileRegions.band(mKeepRegion, screen, TileRegions.KEEP_MARGIN_SCREENS);
            mHasRegions = true;
            synchronized (mTiles) {
                for (Tile t : mTiles) {
                    if (t.image != null && t.isLoaded() && !overlaps(mKeepRegion, t)) {
                        t.yield();
                        t.preloaded = false;
                    }
                }
            }
        }
        Uploader uploader = mUploader;
        if (uploader != null) {
            uploader.addTexture(this);
        }
    }

    private static boolean overlaps(RectF region, Tile t) {
        return region.intersects(t.offsetX, t.offsetY,
                t.offsetX + t.contentWidth, t.offsetY + t.contentHeight);
    }

    /** The not-yet-preloaded tile nearest the screen within the upload band, or null. */
    private Tile nextTileToUpload() {
        if (mRecycled) return null;
        Tile best = null;
        float bestGap = Float.MAX_VALUE;
        for (Tile t : mTiles) {
            if (t.image == null || t.preloaded) continue;
            if (!mHasRegions) return t;
            if (!overlaps(mUploadRegion, t)) continue;
            float gap = TileRegions.gap(mScreenRegion, t.offsetX, t.offsetY,
                    t.offsetX + t.contentWidth, t.offsetY + t.contentHeight);
            if (gap < bestGap) {
                best = t;
                bestGap = gap;
            }
        }
        return best;
    }

    private boolean uploadNextTile(GLCanvas canvas) {
        // Perf trace section (see com.lanraragi.reader.util.TraceSections).
        Trace.beginSection(TraceSections.READER_UPLOAD);
        try {
            return uploadNextTileTraced(canvas);
        } finally {
            Trace.endSection();
        }
    }

    private boolean uploadNextTileTraced(GLCanvas canvas) {
        synchronized (mTiles) {
            Tile next = nextTileToUpload();
            if (next == null) return true;
            // One attempt per tile: a failed upload is retried by the draw-time
            // bind, and retrying here would spin the idle uploader every frame.
            next.preloaded = true;
            boolean hasBeenLoad = next.isLoaded();
            next.updateContent(canvas);

            // It will take some time for a texture to be drawn for the first
            // time. When scrolling, we need to draw several tiles on the screen
            // at the same time. It may cause a UI jank even these textures has
            // been uploaded.
            if (!hasBeenLoad && next.isLoaded()) next.draw(canvas, 0, 0);
            return nextTileToUpload() == null;
        }
    }

    @Override
    public int getWidth() {
        return mWidth;
    }

    @Override
    public int getHeight() {
        return mHeight;
    }

    @RenderThread
    private void syncFrame() {
        if (mFrameDirty.getAndSet(false)) {
            // invalid tiles
            for (Tile tile : mTiles) {
                tile.invalidateContent();
            }
        }
    }

    @Override
    public void draw(GLCanvas canvas, int x, int y) {
        draw(canvas, x, y, mWidth, mHeight);
    }

    // Draws the texture on to the specified rectangle.
    @Override
    public void draw(GLCanvas canvas, int x, int y, int w, int h) {
        RectF src = mSrcRect;
        RectF dest = mDestRect;
        float scaleX = (float) w / mWidth;
        float scaleY = (float) h / mHeight;

        syncFrame();
        for (Tile t : mTiles) {
            src.set(0, 0, t.contentWidth, t.contentHeight);
            src.offset(t.offsetX, t.offsetY);
            mapRect(dest, src, 0, 0, x, y, scaleX, scaleY);
            src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
            canvas.drawTexture(t, src, dest);
        }
    }

    // Draws a sub region of this texture on to the specified rectangle.
    @Override
    public void draw(GLCanvas canvas, RectF source, RectF target) {
        RectF src = mSrcRect;
        RectF dest = mDestRect;
        float x0 = source.left;
        float y0 = source.top;
        float x = target.left;
        float y = target.top;
        float scaleX = target.width() / source.width();
        float scaleY = target.height() / source.height();

        syncFrame();
        for (Tile t : mTiles) {
            src.set(0, 0, t.contentWidth, t.contentHeight);
            src.offset(t.offsetX, t.offsetY);
            if (!src.intersect(source)) {
                continue;
            }
            mapRect(dest, src, x0, y0, x, y, scaleX, scaleY);
            src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
            canvas.drawTexture(t, src, dest);
        }
    }

    // Draws a mixed color of this texture and a specified color onto the
    // a rectangle. The used color is: from * (1 - ratio) + to * ratio.
    public void drawMixed(GLCanvas canvas, int color, float ratio,
                          int x, int y, int width, int height) {
        RectF src = mSrcRect;
        RectF dest = mDestRect;
        float scaleX = (float) width / mWidth;
        float scaleY = (float) height / mHeight;

        syncFrame();
        for (Tile t : mTiles) {
            src.set(0, 0, t.contentWidth, t.contentHeight);
            src.offset(t.offsetX, t.offsetY);
            mapRect(dest, src, 0, 0, x, y, scaleX, scaleY);
            src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
            canvas.drawMixed(t, color, ratio, src, dest);
        }
    }

    public void drawMixed(GLCanvas canvas, int color, float ratio,
                          RectF source, RectF target) {
        RectF src = mSrcRect;
        RectF dest = mDestRect;
        float x0 = source.left;
        float y0 = source.top;
        float x = target.left;
        float y = target.top;
        float scaleX = target.width() / source.width();
        float scaleY = target.height() / source.height();

        syncFrame();
        for (Tile t : mTiles) {
            src.set(0, 0, t.contentWidth, t.contentHeight);
            src.offset(t.offsetX, t.offsetY);
            if (!src.intersect(source)) {
                continue;
            }
            mapRect(dest, src, x0, y0, x, y, scaleX, scaleY);
            src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
            canvas.drawMixed(t, color, ratio, src, dest);
        }
    }

    @Override
    public boolean isOpaque() {
        return mOpaque;
    }

    /** Nothing left to upload ahead of time for the current screen position. */
    public boolean isReady() {
        synchronized (mTiles) {
            return nextTileToUpload() == null;
        }
    }

    public void recycle() {
        mRecycled = true;
        mRunning.lazySet(false);

        for (Tile mTile : mTiles) {
            mTile.free();
        }

        boolean releaseNow;

        synchronized (mImage) {
            if (!mImageBusy) {
                releaseNow = true;
                mImageBusy = true;
            } else {
                releaseNow = false;
                mNeedRelease.lazySet(true);
            }
        }

        if (releaseNow) {
            if (!mReleased.get()) {
                mImage.release();
                mReleased.lazySet(true);
            }
            synchronized (mImage) {
                mImageBusy = false;
            }
        }
    }

    @IntDef({TILE_SMALL, TILE_LARGE})
    @Retention(RetentionPolicy.SOURCE)
    private @interface TileType {
    }

    public interface Callback {
        void invalidateImageTexture(ImageTexture who);
    }

    public static class Uploader implements GLRoot.OnGLIdleListener {
        private final ArrayDeque<ImageTexture> mTextures =
                new ArrayDeque<>(INIT_CAPACITY);

        private final GLRoot mGlRoot;
        private boolean mIsQueued = false;

        public Uploader(GLRoot glRoot) {
            mGlRoot = glRoot;
        }

        public synchronized void clear() {
            mTextures.clear();
        }

        public synchronized void addTexture(ImageTexture t) {
            // Remembered so a later screen move can queue the texture again.
            t.mUploader = this;
            if (t.isReady() || mTextures.contains(t)) return;
            mTextures.addLast(t);

            if (mIsQueued) return;
            mIsQueued = true;
            mGlRoot.addOnGLIdleListener(this);
        }

        @Override
        public boolean onGLIdle(GLCanvas canvas, boolean renderRequested) {
            ArrayDeque<ImageTexture> deque = mTextures;
            synchronized (this) {
                long now = SystemClock.uptimeMillis();
                long dueTime = now + UPLOAD_TILE_LIMIT;
                while (now < dueTime && !deque.isEmpty()) {
                    ImageTexture t = deque.peekFirst();
                    if (t.uploadNextTile(canvas)) {
                        deque.removeFirst();
                        mGlRoot.requestRender();
                    }
                    now = SystemClock.uptimeMillis();
                }
                mIsQueued = !mTextures.isEmpty();

                // return true to keep this listener in the queue
                return mIsQueued;
            }
        }
    }

    private static class Tile extends NativeTexture {

        public int offsetX;
        public int offsetY;
        public ImageWrapper image;
        public int contentWidth;
        public int contentHeight;
        public int borderSize;
        // The idle uploader has handled this tile since it was assigned or yielded.
        public boolean preloaded;
        @TileType
        private int mTileType;

        private static void freeTile(Tile tile, BoundedFreeList<Tile> pool) {
            tile.invalidate();
            if (!pool.offer(tile)) {
                // Pool full: give the texture back instead of keeping it.
                tile.recycle();
            }
        }

        public void setSize(@TileType int tileType, int width, int height) {
            mTileType = tileType;
            int tileSize;
            if (tileType == TILE_SMALL) {
                borderSize = SMALL_BORDER_SIZE;
                tileSize = SMALL_TILE_SIZE;
            } else if (tileType == TILE_LARGE) {
                borderSize = LARGE_BORDER_SIZE;
                tileSize = LARGE_TILE_SIZE;
            } else {
                throw new IllegalStateException("Not support tile type: " + tileType);
            }
            contentWidth = width;
            contentHeight = height;

            mWidth = width + 2 * borderSize;
            mHeight = height + 2 * borderSize;
            mTextureWidth = tileSize;
            mTextureHeight = tileSize;
        }

        @Override
        protected void texImage(boolean init) {
            // Capture to local to avoid race with invalidate() setting image = null
            ImageWrapper localImage = image;
            if (localImage != null && !localImage.isRecycled()) {
                int w, h;
                if (init) {
                    w = mTextureWidth;
                    h = mTextureHeight;
                } else {
                    w = mWidth;
                    h = mHeight;
                }
                localImage.texImage(init, offsetX - borderSize, offsetY - borderSize, w, h);
            }
        }

        private void invalidate() {
            invalidateContent();
            image = null;
            preloaded = false;
        }

        public void free() {
            switch (mTileType) {
                case TILE_SMALL:
                    freeTile(this, sSmallFreeTiles);
                    break;
                case TILE_LARGE:
                    freeTile(this, sLargeFreeTiles);
                    break;
                default:
                    throw new IllegalStateException("Not support tile type: " + mTileType);
            }
        }
    }

    private class AnimateRunnable implements Runnable {

        public void doRun() {
            long lastTime = System.nanoTime();
            long lastDelay = -1L;

            synchronized (mImage) {
                // Check released, image busy, Need release
                if (mReleased.get() || mImage.isImageRecycled() || mImageBusy || mNeedRelease.get()) {
                    mAnimateRunnable = null;
                    return;
                }
                // Obtain image
                mImageBusy = true;
            }

            synchronized (mImage) {
                // Release image
                mImageBusy = false;
                // Check need release, frameCount <= 1
                if (mNeedRelease.get() || !mImage.getAnimated()) {
                    mAnimateRunnable = null;
                    return;
                }
            }

            if (mRequestAnimation.get()) {
                mRunning.lazySet(true);
            }

            for (; ; ) {
                // Obtain
                synchronized (mImage) {
                    // Check released, image busy, Need release, not running
                    if (mReleased.get() || mImage.isImageRecycled() || mImageBusy || mNeedRelease.get() || !mRunning.get()) {
                        mAnimateRunnable = null;
                        return;
                    }
                    // Obtain image
                    mImageBusy = true;
                }

                mImage.start();
                long delay = mImage.getDelay();
                long time = System.nanoTime();
                if (-1L != lastDelay) {
                    delay -= (time - lastTime) / 1000000 - lastDelay;
                }
                lastTime = time;
                lastDelay = delay;
                mImage.advanceFrame();
                mFrameDirty.lazySet(true);
                invalidateSelf();

                synchronized (mImage) {
                    // Release image
                    mImageBusy = false;
                }

                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
        }

        @Override
        public void run() {
            doRun();

            while (mNeedRelease.get()) {
                // Obtain
                synchronized (mImage) {
                    // Check released
                    if (mReleased.get() || mImage.isImageRecycled()) {
                        break;
                    }
                    // Check image busy
                    if (mImageBusy) {
                        // Image is busy, means it is recycling
                        break;
                    }
                    // Obtain image
                    mImageBusy = true;
                }

                if (!mReleased.get()) {
                    mImage.release();
                    mReleased.lazySet(true);
                }

                synchronized (mImage) {
                    // Release image
                    mImageBusy = false;
                }
            }
        }
    }
}
