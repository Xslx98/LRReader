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

class ReadingProgressReconcilerTest {

    @Test
    fun `no server progress keeps local page`() {
        assertEquals(7, ReadingProgressReconciler.resolve(7, 1_000L, 0, 9_999L))
    }

    @Test
    fun `server clearly newer wins`() {
        // serverTs ahead by more than the grace window -> cross-device case
        assertEquals(4, ReadingProgressReconciler.resolve(9, 1_000L, 5, 1_000L + 121L))
    }

    @Test
    fun `local clearly newer wins when non-zero`() {
        assertEquals(9, ReadingProgressReconciler.resolve(9, 2_000L, 5, 2_000L - 121L))
    }

    @Test
    fun `local clearly newer but page zero falls back to max`() {
        // preserves the pre-existing `startPageValue > 0` guard semantics
        assertEquals(4, ReadingProgressReconciler.resolve(0, 2_000L, 5, 2_000L - 121L))
    }

    @Test
    fun `within grace window the furthest page wins - stale racing PUT`() {
        // The regression this fixes: server clock 1s ahead + a stale PUT
        // landed last -> naive serverTs>localTs comparison resumed at 17.
        assertEquals(20, ReadingProgressReconciler.resolve(20, 1_000L, 18, 1_001L))
    }

    @Test
    fun `within grace window server further also wins`() {
        assertEquals(21, ReadingProgressReconciler.resolve(20, 1_001L, 22, 1_000L))
    }

    @Test
    fun `server progress 1 maps to page0 zero`() {
        assertEquals(0, ReadingProgressReconciler.resolve(0, 0L, 1, 100L))
    }

    @Test
    fun `delta exactly at the grace bound stays in the window`() {
        // strict '>' : delta == +GRACE must still fall into the max branch
        assertEquals(
            9,
            ReadingProgressReconciler.resolve(
                9, 1_000L, 5, 1_000L + ReadingProgressReconciler.CLOCK_SKEW_GRACE_SECONDS
            )
        )
    }

    @Test
    fun `negative delta exactly at the grace bound stays in the window`() {
        // strict '<' : delta == -GRACE must still fall into the max branch
        // (would flip to 3 if the guard ever became '<=')
        assertEquals(
            9,
            ReadingProgressReconciler.resolve(
                3, 2_000L, 10, 2_000L - ReadingProgressReconciler.CLOCK_SKEW_GRACE_SECONDS
            )
        )
    }

    // ── normalizeEpochSeconds ──────────────────────────────────────
    // Pure defense for archive_json rows persisted by app versions that
    // stamped System.currentTimeMillis() into `lastreadtime` (pre-unit-
    // unification). Write paths now store epoch seconds; these tests
    // guard the defense against removal while such rows can still exist
    // in installed databases.

    @Test
    fun `normalizeEpochSeconds converts a legacy milliseconds timestamp to seconds`() {
        assertEquals(
            1_700_000_001L,
            ReadingProgressReconciler.normalizeEpochSeconds(1_700_000_001_234L)
        )
    }

    @Test
    fun `normalizeEpochSeconds passes an epoch-seconds timestamp through`() {
        assertEquals(
            1_700_000_000L,
            ReadingProgressReconciler.normalizeEpochSeconds(1_700_000_000L)
        )
    }

    @Test
    fun `normalizeEpochSeconds passes zero through`() {
        assertEquals(0L, ReadingProgressReconciler.normalizeEpochSeconds(0L))
    }

    @Test
    fun `normalizeEpochSeconds threshold is strict greater-than`() {
        // exactly at the ms-detection threshold -> treated as seconds
        assertEquals(
            100_000_000_000L,
            ReadingProgressReconciler.normalizeEpochSeconds(100_000_000_000L)
        )
    }
}
