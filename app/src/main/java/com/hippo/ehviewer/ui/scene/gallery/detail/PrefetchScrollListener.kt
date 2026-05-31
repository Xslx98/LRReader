/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.ui.scene.gallery.detail

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView scroll listener that fires [onPrefetch] for every page
 * position below the current last-visible item, up to [rowsAhead]
 * rows of the [spanCount] grid. Used by the detail-page thumbnail
 * grid to keep `PageThumbnailRepository` requests one viewport ahead
 * of the user.
 *
 * The callback is invoked on each scroll tick — the repository's
 * in-flight registry deduplicates identical positions, so the load on
 * the VM and the server is bounded by what's genuinely new since
 * last tick.
 *
 * **Why not RecyclerView's own prefetching:** RecyclerView's built-in
 * prefetch creates *view holders* ahead of time but does not fetch
 * *content*. Per-page thumbnails come from the server, so we need to
 * trigger the HTTP request explicitly during scroll.
 */
internal class PrefetchScrollListener(
    private val spanCount: Int,
    private val rowsAhead: Int = ROWS_AHEAD_DEFAULT,
    private val onPrefetch: (page: Int) -> Unit,
) : RecyclerView.OnScrollListener() {

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
        val firstVisible = lm.findFirstVisibleItemPosition()
        val lastVisible = lm.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION) return
        val itemCount = lm.itemCount
        if (itemCount == 0) return
        // Cover the visible range too so jump-to-page lands with
        // thumbnails actually fetched (a scrollToPosition() fires
        // onScrolled but the new viewport's first visible item is
        // the *destination* — without including it in the prefetch
        // window the user lands on placeholders).
        val targetEnd = (lastVisible + rowsAhead * spanCount).coerceAtMost(itemCount - 1)
        for (p in firstVisible..targetEnd) {
            onPrefetch(p)
        }
    }

    companion object {
        /**
         * Default number of grid rows below the last-visible row to
         * prefetch.
         *
         * **Cache-budget invariant:** the scroll working set is the
         * visible viewport plus these prefetch rows. It must stay below
         * [com.lanraragi.reader.client.api.PageThumbnailCache]'s entry
         * capacity, otherwise the LRU evicts tiles that are still on
         * screen and they flicker on rebind. Growing this (or the grid's
         * span count) widens the working set — re-check the cache budget
         * tiers and the working-set assertion in PageThumbnailCacheBudgetTest.
         */
        const val ROWS_AHEAD_DEFAULT = 3
    }
}
