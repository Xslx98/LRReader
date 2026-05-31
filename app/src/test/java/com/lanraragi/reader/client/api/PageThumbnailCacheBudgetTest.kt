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

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [PageThumbnailCache.budgetBytesFor].
 *
 * The cache must hold the detail-page grid's *scroll working set* — the
 * visible viewport (≈3 rows × 3 cols) plus
 * [com.hippo.ehviewer.ui.scene.gallery.detail.PrefetchScrollListener]'s
 * 3 prefetch rows ≈ 18 distinct pages, before any scroll-back. If the
 * LRU is smaller than this it evicts tiles that are still on screen and
 * they flicker on rebind. These tests pin the budget above the working
 * set on every heap tier so a future shrink fails CI.
 */
class PageThumbnailCacheBudgetTest {

    /** Pages the grid touches at once at the scroll frontier. */
    private val workingSetEntries = 18

    /** Worst-case per-entry size after the RGB_565 + downsample decode. */
    private val worstCaseEntryBytes = 700L * 1024

    @Test
    fun budget_lowHeapStillExceedsWorkingSet() {
        val low = PageThumbnailCache.budgetBytesFor(384L * 1024 * 1024)
        val entries = low / worstCaseEntryBytes
        assertTrue(
            "low-heap budget ($low bytes ≈ $entries entries) must hold the $workingSetEntries-page working set",
            entries > workingSetEntries,
        )
    }

    @Test
    fun budget_growsWithHeap() {
        val low = PageThumbnailCache.budgetBytesFor(384L * 1024 * 1024)
        val mid = PageThumbnailCache.budgetBytesFor(768L * 1024 * 1024)
        val high = PageThumbnailCache.budgetBytesFor(2048L * 1024 * 1024)
        assertTrue("mid heap budget should exceed low", mid > low)
        assertTrue("high heap budget should exceed mid", high > mid)
    }

    @Test
    fun budget_isClampedOnHugeHeap() {
        // A 6 GB heap must not hand the page-thumb grid an unbounded
        // cache; it is a secondary surface behind the cover-image cache.
        val huge = PageThumbnailCache.budgetBytesFor(6L * 1024 * 1024 * 1024)
        assertTrue("huge-heap budget must stay capped", huge <= 64L * 1024 * 1024)
    }
}
