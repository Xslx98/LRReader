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
 * **Size budget:** byte-sized LRU at [budgetBytesFor], a heap-tiered
 * budget mirroring the cover-image cache in
 * [com.hippo.ehviewer.module.ClientModule]. It must exceed the grid's
 * *scroll working set* — the visible viewport (≈3 rows × 3 cols) plus
 * [com.hippo.ehviewer.ui.scene.gallery.detail.PrefetchScrollListener]'s
 * 3 prefetch rows ≈ 18 distinct pages, plus a screen of scroll-back.
 * The previous fixed 6 MB budget assumed an optimistic 350 KB decode;
 * real page thumbnails decode larger, so the cache held fewer entries
 * than the working set and the LRU evicted tiles that were still on
 * screen. On rebind those cells missed the cache, blanked, re-fetched
 * and crossfaded back in — the preview grid flickered during scroll.
 * Paired with the RGB_565 + downsample decode in
 * [PageThumbnailRepository], the tiered budget now holds the working
 * set with scroll-back headroom on every heap tier.
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

    private const val MB: Long = 1024L * 1024

    // Heap tiers (per-app limit from Runtime.maxMemory()), matching the
    // thresholds the cover-image cache uses in ClientModule so the two
    // caches scale together rather than fighting for the same heap.
    private const val TIER_LOW: Long = 512 * MB
    private const val TIER_MID: Long = 1024 * MB
    private const val TIER_HIGH: Long = 3072 * MB

    // Page-thumb budgets per tier. Smaller than the cover cache (this is
    // a secondary surface) but every tier clears the ~18-page scroll
    // working set plus scroll-back: at the RGB_565 + downsampled ≈700 KB
    // worst-case entry size, 16 MB ≈ 23 entries, 48 MB ≈ 68.
    private const val BUDGET_LOW: Long = 16 * MB
    private const val BUDGET_MID: Long = 24 * MB
    private const val BUDGET_HIGH: Long = 40 * MB
    private const val BUDGET_ULTRA: Long = 48 * MB

    /**
     * Resolve the cache byte budget for the given per-app heap limit.
     * Tiered so low-RAM devices stay conservative while still holding
     * the grid's scroll working set:
     *  - `< 512 MB` heap → 16 MB
     *  - `< 1 GB`  heap → 24 MB
     *  - `< 3 GB`  heap → 40 MB
     *  - `>= 3 GB` heap → 48 MB
     */
    internal fun budgetBytesFor(maxMemoryBytes: Long): Int = when {
        maxMemoryBytes < TIER_LOW -> BUDGET_LOW
        maxMemoryBytes < TIER_MID -> BUDGET_MID
        maxMemoryBytes < TIER_HIGH -> BUDGET_HIGH
        else -> BUDGET_ULTRA
    }.toInt()

    private val lru = object : LruCache<String, Bitmap>(
        budgetBytesFor(Runtime.getRuntime().maxMemory())
    ) {
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
