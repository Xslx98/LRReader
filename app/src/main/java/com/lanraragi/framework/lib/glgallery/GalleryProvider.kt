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

package com.lanraragi.framework.lib.glgallery

import android.util.LruCache
import androidx.annotation.AnyThread
import androidx.annotation.IntDef
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.core.util.Pools
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.image.ImageTexture
import com.lanraragi.framework.lib.glview.image.ImageWrapper
import com.lanraragi.framework.lib.glview.view.GLRoot
import com.lanraragi.framework.lib.image.Image
// import com.lanraragi.framework.lib.image.Image1
import com.lanraragi.reader.util.MemoryTrim
import com.lanraragi.reader.util.MemoryTrimmable
import com.lanraragi.reader.util.OSUtils

/**
 * @param cacheMaxBytes budget of the decoded-page memory cache (tests pass
 * a small one to exercise the over-budget path).
 */
abstract class GalleryProvider @VisibleForTesting protected constructor(
    cacheMaxBytes: Int,
) : MemoryTrimmable {

    private val notifyTaskPool = Pools.SynchronizedPool<NotifyTask>(5)

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var glRoot: GLRoot? = null

    private val imageCache: ImageCache = ImageCache(cacheMaxBytes)

    protected constructor() : this(
        MemoryTrim.readerCacheBytes(OSUtils.getTotalMemory(), MemoryTrim.isLowRamDevice())
    )

    private var started = false

    // Written on the UI thread in stop(), read on decoder threads in
    // notifyPageSucceed — must be volatile.
    @Volatile
    private var stopped = false

    @UiThread
    open fun start() {
        OSUtils.checkMainLoop()

        check(!started) { "Can't start it twice" }
        started = true
        MemoryTrim.register(this)
    }

    @UiThread
    open fun stop() {
        OSUtils.checkMainLoop()
        stopped = true
        MemoryTrim.unregister(this)
        imageCache.evictAll()
        ImageTexture.trimFreeTiles()
    }

    /**
     * Audit 2026-10-04 C12: give decoded pages back under memory pressure. TRIM
     * keeps the most recently used quarter (the pages around the current one);
     * CLEAR drops all of them. Pages on screen hold their own reference.
     * Pooled free GL tiles are pure reuse cache and go on either action
     * (audit 2026-10-13 PERF-02).
     */
    @UiThread
    override fun onTrimMemory(action: MemoryTrim.Action) {
        ImageTexture.trimFreeTiles()
        if (action == MemoryTrim.Action.CLEAR) {
            imageCache.evictAll()
        } else if (action == MemoryTrim.Action.TRIM) {
            imageCache.trimToSize(imageCache.maxSize() / 4)
        }
    }

    fun setGLRoot(glRoot: GLRoot?) {
        this.glRoot = glRoot
    }

    /**
     * Schedule [callback] to fire on the GL render thread strictly AFTER any
     * NotifyTask currently queued via [notifyDataChanged] et al has
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
    protected fun postBarrierAfterPendingNotifications(callback: Runnable) {
        val glRoot = this.glRoot
        if (glRoot == null) {
            callback.run()
            return
        }
        glRoot.addOnGLIdleListener { _, _ ->
            callback.run()
            false
        }
    }

    /**
     * @return [STATE_WAIT] for wait,
     * [STATE_ERROR] for error, [getError] to get error message,
     * 0 for empty
     */
    abstract fun size(): Int

    fun request(index: Int) {
        val imageWrapper = imageCache.get(index)
        if (imageWrapper != null) {
            notifyPageSucceed(index, imageWrapper)
        } else {
            onRequest(index)
        }
    }

    fun forceRequest(index: Int) {
        onForceRequest(index)
    }

    fun removeCache(index: Int) {
        imageCache.remove(index)
    }

    /**
     * @return true when a decoded image for `index` currently sits in
     * the memory cache. Lets providers skip redundant preload decodes — the
     * LruCache stays the single source of truth (the `get` also bumps
     * the entry's recency, which is desirable for pages near the reader).
     */
    fun hasCache(index: Int): Boolean = imageCache.get(index) != null

    protected abstract fun onRequest(index: Int)

    protected abstract fun onForceRequest(index: Int)

    fun cancelRequest(index: Int) {
        onCancelRequest(index)
    }

    protected abstract fun onCancelRequest(index: Int)

    abstract fun getError(): String?

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun notifyDataChanged() {
        notify(NotifyTask.TYPE_DATA_CHANGED, -1, 0.0f, null, null)
    }

    fun notifyDataChanged(index: Int) {
        notify(NotifyTask.TYPE_DATA_CHANGED, index, 0.0f, null, null)
    }

    fun notifyPageWait(index: Int) {
        notify(NotifyTask.TYPE_WAIT, index, 0.0f, null, null)
    }

    fun notifyPagePercent(index: Int, percent: Float) {
        notify(NotifyTask.TYPE_PERCENT, index, percent, null, null)
    }

    fun notifyPageSucceed(index: Int, image: Image) {
        val imageWrapper = ImageWrapper(image)
        if (stopped) {
            // A decode completing after stop() must not repopulate the
            // already-evicted cache — nothing would evict the entry again and
            // the page would be retained until the provider graph is GC'd.
            // Recycle it instead (release at zero references recycles the
            // underlying Image, animated or not).
            imageWrapper.release()
            return
        }
        imageCache.add(index, imageWrapper)
        notifyPageSucceed(index, imageWrapper)
    }

    fun notifyPageSucceed(index: Int, image: ImageWrapper) {
        notify(NotifyTask.TYPE_SUCCEED, index, 0.0f, image, null)
    }

    fun notifyPageFailed(index: Int, error: String?) {
        notify(NotifyTask.TYPE_FAILED, index, 0.0f, null, error)
    }

    private fun notify(@NotifyTask.Type type: Int, index: Int, percent: Float, image: ImageWrapper?, error: String?) {
        val listener = this.listener
        val glRoot = this.glRoot
        if (listener == null || glRoot == null) {
            // The provider is being torn down (listener/GLRoot already cleared)
            // yet an in-flight decode still completed. A successful animated
            // page is never retained by imageCache (see ImageCache.add), so if
            // we drop the notification here nothing will ever recycle its
            // native image. Release it to free the underlying Image. The same
            // holds for a page bigger than the cache budget. Cached pages are
            // owned by the cache and must NOT be released.
            if (type == NotifyTask.TYPE_SUCCEED && image != null &&
                image.isUncached
            ) {
                image.release()
            }
            return
        }

        val task = notifyTaskPool.acquire() ?: NotifyTask(listener, notifyTaskPool)
        task.setData(type, index, percent, image, error)
        glRoot.addOnGLIdleListener(task)
    }

    private class NotifyTask(
        private val listener: Listener,
        private val pool: Pools.Pool<NotifyTask>,
    ) : GLRoot.OnGLIdleListener {

        @IntDef(TYPE_DATA_CHANGED, TYPE_WAIT, TYPE_PERCENT, TYPE_SUCCEED, TYPE_FAILED)
        @Retention(AnnotationRetention.SOURCE)
        annotation class Type

        @Type
        private var type = 0
        private var index = 0
        private var percent = 0f
        private var image: ImageWrapper? = null
        private var error: String? = null

        fun setData(@Type type: Int, index: Int, percent: Float, image: ImageWrapper?, error: String?) {
            this.type = type
            this.index = index
            this.percent = percent
            this.image = image
            this.error = error
        }

        override fun onGLIdle(canvas: GLCanvas?, renderRequested: Boolean): Boolean {
            when (type) {
                TYPE_DATA_CHANGED -> if (index < 0) {
                    listener.onDataChanged()
                } else {
                    listener.onDataChanged(index)
                }
                TYPE_WAIT -> listener.onPageWait(index)
                TYPE_PERCENT -> listener.onPagePercent(index, percent)
                // notifyPageSucceed always passes an image.
                TYPE_SUCCEED -> image?.let { listener.onPageSucceed(index, it) }
                TYPE_FAILED -> listener.onPageFailed(index, error)
            }

            // Clean data
            image = null
            error = null
            // Push back
            pool.release(this)

            return false
        }

        companion object {
            const val TYPE_DATA_CHANGED = 0
            const val TYPE_WAIT = 1
            const val TYPE_PERCENT = 2
            const val TYPE_SUCCEED = 3
            const val TYPE_FAILED = 4
        }
    }

    // Budget: MemoryTrim.readerCacheBytes (RAM/12, 64..256 MB, 96 MB on
    // low-RAM devices). A FIT page decodes near screen size (Image
    // readerSampleSize), about 10-15 MB as ARGB_8888.
    private class ImageCache(maxBytes: Int) : LruCache<Int, ImageWrapper>(maxBytes) {

        fun add(key: Int, value: ImageWrapper) {
            if (value.animated) {
                return
            }
            if (sizeOf(key, value) > maxSize()) {
                // Audit 2026-10-06 N3: LruCache.trimToSize would evict an entry
                // bigger than the whole budget the moment it is put, releasing
                // it to zero references (recycled) before the GL thread can
                // obtain it — the adapter then re-requested the page forever.
                // Hand it over uncached instead, like an animated page.
                value.markCacheBypassed()
                return
            }
            if (value.obtain()) {
                put(key, value)
            }
//            if (value.getFormat() != Image1.FORMAT_GIF && value.getFormat() == Image1.FORMAT_WEBP && value.obtain()) {
//                put(key, value);
//            }
        }

        override fun sizeOf(key: Int, value: ImageWrapper): Int {
            // Real resident bytes (audit PERF-07): a 16-bit PNG or 10-bit
            // AVIF/HEIF page keeps its source bitmap plus an ARGB_8888 upload
            // copy, up to 12 bytes per pixel instead of the 4 counted before.
            return value.byteCount
        }

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: ImageWrapper?, newValue: ImageWrapper?) {
            oldValue?.release()
        }
    }

    interface Listener {

        fun onDataChanged()

        fun onPageWait(index: Int)

        fun onPagePercent(index: Int, percent: Float)

        fun onPageSucceed(index: Int, image: ImageWrapper)

        fun onPageFailed(index: Int, error: String?)

        fun onDataChanged(index: Int)
    }

    companion object {
        const val STATE_WAIT = -1
        const val STATE_ERROR = -2
    }
}
