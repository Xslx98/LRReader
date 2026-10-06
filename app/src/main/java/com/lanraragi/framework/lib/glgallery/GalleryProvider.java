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

package com.lanraragi.framework.lib.glgallery;

import android.util.LruCache;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas;
import com.lanraragi.framework.lib.glview.image.ImageWrapper;
import com.lanraragi.framework.lib.glview.view.GLRoot;
import com.lanraragi.framework.lib.image.Image;
//import com.lanraragi.framework.lib.image.Image1;
import com.lanraragi.framework.lib.yorozuya.ConcurrentPool;
import com.lanraragi.framework.lib.yorozuya.OSUtils;
import com.lanraragi.reader.util.MemoryTrim;
import com.lanraragi.reader.util.MemoryTrimmable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public abstract class GalleryProvider implements MemoryTrimmable {

    public static final int STATE_WAIT = -1;
    public static final int STATE_ERROR = -2;

    private final ConcurrentPool<NotifyTask> mNotifyTaskPool = new ConcurrentPool<>(5);
    private volatile Listener mListener;
    private volatile GLRoot mGLRoot;

    private final ImageCache mImageCache;

    protected GalleryProvider() {
        this(MemoryTrim.readerCacheBytes(OSUtils.getTotalMemory(), MemoryTrim.isLowRamDevice()));
    }

    /**
     * @param cacheMaxBytes budget of the decoded-page memory cache (tests pass
     *                      a small one to exercise the over-budget path).
     */
    @VisibleForTesting
    protected GalleryProvider(int cacheMaxBytes) {
        mImageCache = new ImageCache(cacheMaxBytes);
    }

    private boolean mStarted = false;
    // Written on the UI thread in stop(), read on decoder threads in
    // notifyPageSucceed — must be volatile.
    private volatile boolean mStopped = false;

    @UiThread
    public void start() {
        OSUtils.checkMainLoop();

        if (mStarted) {
            throw new IllegalStateException("Can't start it twice");
        }
        mStarted = true;
        MemoryTrim.register(this);
    }

    @UiThread
    public void stop() {
        OSUtils.checkMainLoop();
        mStopped = true;
        MemoryTrim.unregister(this);
        mImageCache.evictAll();
    }

    /**
     * Audit 2026-10-04 C12: give decoded pages back under memory pressure. TRIM
     * keeps the most recently used quarter (the pages around the current one);
     * CLEAR drops all of them. Pages on screen hold their own reference.
     */
    @UiThread
    @Override
    public void onTrimMemory(@NonNull MemoryTrim.Action action) {
        if (action == MemoryTrim.Action.CLEAR) {
            mImageCache.evictAll();
        } else if (action == MemoryTrim.Action.TRIM) {
            mImageCache.trimToSize(mImageCache.maxSize() / 4);
        }
    }

    public void setGLRoot(GLRoot glRoot) {
        mGLRoot = glRoot;
    }

    /**
     * Schedule [callback] to fire on the GL render thread strictly AFTER any
     * NotifyTask currently queued via {@link #notifyDataChanged()} et al has
     * been dispatched. Subclasses can use this to install a barrier that
     * fires once the render thread has consumed every pending data-change
     * notification — useful for sequencing a follow-up render-thread call
     * (e.g. setCurrentPageScrollFraction) so it cannot land in the same
     * frame as the onDataChanged that would reset the layout state.
     *
     * If GLRoot has not yet been wired up (provider not attached to an
     * Activity GL surface), the callback runs synchronously on the caller's
     * thread to avoid silently dropping it.
     */
    @AnyThread
    protected final void postBarrierAfterPendingNotifications(Runnable callback) {
        GLRoot glRoot = mGLRoot;
        if (glRoot == null) {
            callback.run();
            return;
        }
        glRoot.addOnGLIdleListener((canvas, renderRequested) -> {
            callback.run();
            return false;
        });
    }

    /**
     * @return {@link #STATE_WAIT} for wait,
     * {@link #STATE_ERROR} for error, {@link #getError()} to get error message,
     * 0 for empty
     */
    public abstract int size();

    public final void request(int index) {
        ImageWrapper imageWrapper = mImageCache.get(index);
        if (imageWrapper != null) {
            notifyPageSucceed(index, imageWrapper);
        } else {
            onRequest(index);
        }
    }

    public final void forceRequest(int index) {
        onForceRequest(index);
    }

    public void removeCache(int index) {
        mImageCache.remove(index);
    }

    /**
     * @return true when a decoded image for {@code index} currently sits in
     * the memory cache. Lets providers skip redundant preload decodes — the
     * LruCache stays the single source of truth (the {@code get} also bumps
     * the entry's recency, which is desirable for pages near the reader).
     */
    public final boolean hasCache(int index) {
        return mImageCache.get(index) != null;
    }

    protected abstract void onRequest(int index);

    protected abstract void onForceRequest(int index);

    public final void cancelRequest(int index) {
        onCancelRequest(index);
    }

    protected abstract void onCancelRequest(int index);

    public abstract String getError();

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public void notifyDataChanged() {
        notify(NotifyTask.TYPE_DATA_CHANGED, -1, 0.0f, null, null);
    }

    public void notifyDataChanged(int index) {
        notify(NotifyTask.TYPE_DATA_CHANGED, index, 0.0f, null, null);
    }

    public void notifyPageWait(int index) {
        notify(NotifyTask.TYPE_WAIT, index, 0.0f, null, null);
    }

    public void notifyPagePercent(int index, float percent) {
        notify(NotifyTask.TYPE_PERCENT, index, percent, null, null);
    }

    public void notifyPageSucceed(int index, Image image) {
        ImageWrapper imageWrapper = new ImageWrapper(image);
        if (mStopped) {
            // A decode completing after stop() must not repopulate the
            // already-evicted cache — nothing would evict the entry again and
            // the page would be retained until the provider graph is GC'd.
            // Recycle it instead (release at zero references recycles the
            // underlying Image, animated or not).
            imageWrapper.release();
            return;
        }
        mImageCache.add(index, imageWrapper);
        notifyPageSucceed(index, imageWrapper);
    }

    public void notifyPageSucceed(int index, ImageWrapper image) {
        notify(NotifyTask.TYPE_SUCCEED, index, 0.0f, image, null);
    }

    public void notifyPageFailed(int index, String error) {
        notify(NotifyTask.TYPE_FAILED, index, 0.0f, null, error);
    }

    private void notify(@NotifyTask.Type int type, int index, float percent, ImageWrapper image, String error) {
        Listener listener = mListener;
        GLRoot glRoot = mGLRoot;
        if (listener == null || glRoot == null) {
            // The provider is being torn down (listener/GLRoot already cleared)
            // yet an in-flight decode still completed. A successful animated
            // page is never retained by mImageCache (see ImageCache.add), so if
            // we drop the notification here nothing will ever recycle its
            // native image. Release it to free the underlying Image. The same
            // holds for a page bigger than the cache budget. Cached pages are
            // owned by the cache and must NOT be released.
            if (type == NotifyTask.TYPE_SUCCEED && image != null
                    && image.isUncached()) {
                image.release();
            }
            return;
        }

        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask(listener, mNotifyTaskPool);
        }
        task.setData(type, index, percent, image, error);
        glRoot.addOnGLIdleListener(task);
    }

    private static class NotifyTask implements GLRoot.OnGLIdleListener {

        @IntDef({TYPE_DATA_CHANGED, NotifyTask.TYPE_WAIT, TYPE_PERCENT, TYPE_SUCCEED, TYPE_FAILED})
        @Retention(RetentionPolicy.SOURCE)
        public @interface Type {
        }

        public static final int TYPE_DATA_CHANGED = 0;
        public static final int TYPE_WAIT = 1;
        public static final int TYPE_PERCENT = 2;
        public static final int TYPE_SUCCEED = 3;
        public static final int TYPE_FAILED = 4;

        private final Listener mListener;
        private final ConcurrentPool<NotifyTask> mPool;

        @Type
        private int mType;
        private int mIndex;
        private float mPercent;
        private ImageWrapper mImage;
        private String mError;

        public NotifyTask(Listener listener, ConcurrentPool<NotifyTask> pool) {
            mListener = listener;
            mPool = pool;
        }

        public void setData(@Type int type, int index, float percent, ImageWrapper image, String error) {
            mType = type;
            mIndex = index;
            mPercent = percent;
            mImage = image;
            mError = error;
        }

        @Override
        public boolean onGLIdle(GLCanvas canvas, boolean renderRequested) {
            switch (mType) {
                case TYPE_DATA_CHANGED:
                    if (mIndex < 0) {
                        mListener.onDataChanged();
                    } else {
                        mListener.onDataChanged(mIndex);
                    }
                    break;
                case TYPE_WAIT:
                    mListener.onPageWait(mIndex);
                    break;
                case TYPE_PERCENT:
                    mListener.onPagePercent(mIndex, mPercent);
                    break;
                case TYPE_SUCCEED:
                    mListener.onPageSucceed(mIndex, mImage);
                    break;
                case TYPE_FAILED:
                    mListener.onPageFailed(mIndex, mError);
                    break;
            }

            // Clean data
            mImage = null;
            mError = null;
            // Push back
            mPool.push(this);

            return false;
        }
    }

    private static class ImageCache extends LruCache<Integer, ImageWrapper> {

        // Budget: MemoryTrim.readerCacheBytes (RAM/8, 64..256 MB, 96 MB on low-RAM
        // devices). Large pages are ~15 MB at 1600x2400x4.

        public ImageCache(int maxBytes) {
            super(maxBytes);
        }

        public void add(Integer key, ImageWrapper value) {
            if (value.getAnimated()) {
                return;
            }
            if (sizeOf(key, value) > maxSize()) {
                // Audit 2026-10-06 N3: LruCache.trimToSize would evict an entry
                // bigger than the whole budget the moment it is put, releasing
                // it to zero references (recycled) before the GL thread can
                // obtain it — the adapter then re-requested the page forever.
                // Hand it over uncached instead, like an animated page.
                value.markCacheBypassed();
                return;
            }
            if (value.obtain()) {
                put(key, value);
            }
//            if (value.getFormat() != Image1.FORMAT_GIF && value.getFormat() == Image1.FORMAT_WEBP && value.obtain()) {
//                put(key, value);
//            }
        }

        @Override
        protected int sizeOf(Integer key, ImageWrapper value) {
            int size = value.getWidth() * value.getHeight() * 4;
//            if (value.getFormat() == Image1.FORMAT_GIF || value.getFormat() == Image1.FORMAT_WEBP) {
//                size *= 5;
//            }
//            return size;
            return value.getWidth() * value.getHeight() * 4;
        }

        @Override
        protected void entryRemoved(boolean evicted, Integer key, ImageWrapper oldValue, ImageWrapper newValue) {
            if (oldValue != null) {
                oldValue.release();
            }
        }
    }

    public interface Listener {

        void onDataChanged();

        void onPageWait(int index);

        void onPagePercent(int index, float percent);

        void onPageSucceed(int index, ImageWrapper image);

        void onPageFailed(int index, String error);

        void onDataChanged(int index);
    }
}
