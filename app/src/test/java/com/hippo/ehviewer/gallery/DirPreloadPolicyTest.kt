/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class DirPreloadPolicyTest {

    @Test
    fun `decode at the anchor preloads the next radius pages`() {
        assertEquals(
            listOf(6, 7),
            DirPreloadPolicy.preloadTargets(5, 5, 100, 2) { false }
        )
    }

    @Test
    fun `march past the reader window is cut off`() {
        // decodedIndex=7 with anchor=5, radius=2 -> limit is 7 -> nothing past it.
        // Pre-fix this spawned 8,9 and the chain ran to the end of the archive.
        assertEquals(
            emptyList<Int>(),
            DirPreloadPolicy.preloadTargets(7, 5, 100, 2) { false }
        )
    }

    @Test
    fun `partial window at the boundary`() {
        // decodedIndex=6, anchor=5 -> limit 7 -> only 7 qualifies
        assertEquals(
            listOf(7),
            DirPreloadPolicy.preloadTargets(6, 5, 100, 2) { false }
        )
    }

    @Test
    fun `cached pages are skipped`() {
        assertEquals(
            listOf(7),
            DirPreloadPolicy.preloadTargets(5, 5, 100, 2) { it == 6 }
        )
    }

    @Test
    fun `clamped at the end of the archive`() {
        assertEquals(
            listOf(99),
            DirPreloadPolicy.preloadTargets(98, 98, 100, 2) { false }
        )
    }

    @Test
    fun `backward jump re-anchors - a late far decode must not march`() {
        // user jumped back to page 3; the just-finished decode of page 50
        // (stale current-priority request) must not spawn preloads at 51,52
        assertEquals(
            emptyList<Int>(),
            DirPreloadPolicy.preloadTargets(50, 3, 100, 2) { false }
        )
    }

    @Test
    fun `forward jump - a stale decode behind the anchor must not march`() {
        // user jumped from page 3 to page 150; a stale preload decode of
        // page 3 finishing late must not bridge the gap
        assertEquals(
            emptyList<Int>(),
            DirPreloadPolicy.preloadTargets(3, 150, 200, 2) { false }
        )
    }

    @Test
    fun `fast page turns - catch-up within radius still preloads`() {
        // anchor is 2 ahead of the decode (reader flipping fast): still
        // inside the window, must keep feeding pages
        assertEquals(
            listOf(6, 7),
            DirPreloadPolicy.preloadTargets(5, 7, 100, 2) { false }
        )
    }

    @Test
    fun `radius zero preloads nothing`() {
        assertEquals(
            emptyList<Int>(),
            DirPreloadPolicy.preloadTargets(5, 5, 100, 0) { false }
        )
    }
}
