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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit tests for [PageThumbnailRepository.computeInSampleSize].
 *
 * The downsample guard keeps a single decoded page-thumbnail from
 * ballooning to many megabytes: an oversized server thumbnail is
 * subsampled toward the on-screen tile size so [PageThumbnailCache]'s
 * byte budget holds the grid's whole scroll working set instead of
 * evicting tiles that are still visible (the cause of the preview grid
 * flickering during scroll).
 */
class PageThumbnailDecodeTest {

    @Test
    fun inSampleSize_noDownsampleWhenSourceFitsTarget() {
        // A ~500px server thumbnail into a ~480px tile target stays 1:1.
        assertEquals(1, PageThumbnailRepository.computeInSampleSize(500, 700, 480, 720))
    }

    @Test
    fun inSampleSize_neverUpscalesSmallSource() {
        assertEquals(1, PageThumbnailRepository.computeInSampleSize(240, 360, 480, 720))
    }

    @Test
    fun inSampleSize_halvesOversizedSource() {
        // 1000x1500 into a 480x720 target → /2 keeps both dims >= target.
        assertEquals(2, PageThumbnailRepository.computeInSampleSize(1000, 1500, 480, 720))
    }

    @Test
    fun inSampleSize_stopsAtLargestSubsampleAtOrAboveTarget() {
        // 2000x2800 into 480x720: /4 would drop height to 700 (< 720), so
        // the canonical recipe keeps /2 (1400 >= 720) — never undershoots.
        assertEquals(2, PageThumbnailRepository.computeInSampleSize(2000, 2800, 480, 720))
    }

    @Test
    fun inSampleSize_quartersVeryLargeSource() {
        // 2000x3200 into 480x720 → /4 (height 800 >= 720, /8 would be 400).
        assertEquals(4, PageThumbnailRepository.computeInSampleSize(2000, 3200, 480, 720))
    }

    @Test
    fun inSampleSize_guardsAgainstUndecodableBounds() {
        // BitmapFactory reports -1 / 0 outWidth/outHeight on a failed
        // bounds pass — must not divide-by-zero or return < 1.
        assertEquals(1, PageThumbnailRepository.computeInSampleSize(-1, -1, 480, 720))
        assertEquals(1, PageThumbnailRepository.computeInSampleSize(0, 0, 480, 720))
    }
}
