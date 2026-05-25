/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.client.api

import android.graphics.Bitmap
import android.util.LruCache
import com.hippo.ehviewer.client.LRRCacheKeyFactory
import com.hippo.ehviewer.module.Cacheable

/**
 * In-memory LRU of per-page thumbnails for the detail-page preview
 * grid. Decoded `Bitmap`s are kept under
 * [LRRCacheKeyFactory.getPageThumbKey] so the cover thumbnail
 * (`preview:large:...`) and the page thumbnail (`preview:page:...`)
 * never collide.
 *
 * **Why a custom cache and not Conaco:** the LANraragi
 * `GET /api/archives/:id/thumbnail?page=N` endpoint can answer 202
 * with a JSON job descriptor instead of an image when the thumbnail
 * is still being generated. Conaco's download/decode pipeline does
 * not branch on status code, so feeding it a 202 body would surface
 * as a "broken image" decode failure. Keeping the per-page path on
 * a dedicated cache also bounds its memory budget independently
 * from cover thumbnails — a long manhwa filling this cache cannot
 * evict the user's recently-browsed cover thumbnails.
 *
 * **Size budget:** byte-sized LRU at [MAX_BYTES]. Tiered against the
 * 250×350 ≈ 350 KB JPEG decoded size typical of LANraragi server
 * thumbnails, so a 6 MB budget holds ~17 entries — enough for the
 * default 3-column × 5-row visible viewport plus 3 rows of
 * prefetch.
 *
 * **Lifetime:** cleared on profile switch via [Cacheable.clearCache]
 * (registered in [com.hippo.ehviewer.module.ClientModule]). The
 * Repository also calls [invalidate] when a new archive enters the
 * detail page so stale thumbs from the previous archive cannot leak
 * into the new grid via a fast back-then-forward navigation.
 *
 * **Thread safety:** all mutations are synchronized on the LRU
 * instance. Bitmaps stored here are immutable from the writer's
 * perspective once `put` returns, so concurrent `get` calls are
 * safe without per-entry locking.
 *
 * **Bitmap recycling:** evicted bitmaps are **not** recycled here.
 * Recycling on `entryRemoved` is unsafe because a view holder may
 * still be drawing the previous frame from the bitmap. The bitmap
 * is reclaimed by GC once its last referrer (the ImageView, the
 * LruCache, any pending coroutine result) releases it — small
 * memory cost for a sizeable correctness win.
 */
object PageThumbnailCache : Cacheable {

    /**
     * Maximum total byte size of cached bitmaps. Hand-tuned against
     * LANraragi default thumbnail dimensions (≈350 KB decoded).
     * 6 MB ≈ 17 entries.
     */
    private const val MAX_BYTES: Int = 6 * 1024 * 1024

    private val lru = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Look up the cached bitmap for ([arcid], [page]). Returns null on
     * cache miss.
     */
    fun get(arcid: String, page: Int): Bitmap? {
        val key = LRRCacheKeyFactory.getPageThumbKey(arcid, page)
        return synchronized(lru) { lru.get(key) }
    }

    /**
     * Store [bitmap] for ([arcid], [page]). Overwrites any prior entry
     * for the same key.
     */
    fun put(arcid: String, page: Int, bitmap: Bitmap) {
        val key = LRRCacheKeyFactory.getPageThumbKey(arcid, page)
        synchronized(lru) { lru.put(key, bitmap) }
    }

    /**
     * Drop every cached bitmap whose key belongs to [arcid]. Called by
     * the ViewModel when the user navigates from one detail page to
     * another so the new grid does not flash the previous archive's
     * pages.
     */
    fun invalidate(arcid: String) {
        val prefix = "preview:page:$arcid:"
        synchronized(lru) {
            val keys = lru.snapshot().keys.filter { it.startsWith(prefix) }
            for (key in keys) {
                lru.remove(key)
            }
        }
    }

    override fun clearCache() {
        synchronized(lru) { lru.evictAll() }
    }
}
