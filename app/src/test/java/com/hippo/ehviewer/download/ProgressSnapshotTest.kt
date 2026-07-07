/*
 * Copyright 2026 LRReader contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProgressSnapshotTest {

    @Test
    fun `default snapshot has -1 speed, -1 remaining, 0 progress, -1 total`() {
        val s = ProgressSnapshot.initial("abc")
        assertEquals("abc", s.arcid)
        assertEquals(-1L, s.speed)
        assertEquals(-1L, s.remaining)
        assertEquals(0, s.finished)
        assertEquals(0, s.downloaded)
        assertEquals(-1, s.total)
    }

    @Test
    fun `equal snapshots compare equal`() {
        val a = ProgressSnapshot("x", 10L, 1, 1, 10, 5000L)
        val b = ProgressSnapshot("x", 10L, 1, 1, 10, 5000L)
        assertEquals(a, b)
    }

    @Test
    fun `snapshots with different arcid are not equal`() {
        val a = ProgressSnapshot("x", 10L, 1, 1, 10, 5000L)
        val b = ProgressSnapshot("y", 10L, 1, 1, 10, 5000L)
        assertNotEquals(a, b)
    }

    @Test
    fun `copyWith updates only specified fields`() {
        val a = ProgressSnapshot("x", 10L, 1, 1, 10, 5000L)
        val b = a.copyWith(speed = 20L)
        assertEquals(20L, b.speed)
        assertEquals(a.finished, b.finished)
        assertEquals(a.total, b.total)
    }

    @Test
    fun `partialPages defaults to zero`() {
        assertEquals(0f, ProgressSnapshot.initial("abc").partialPages, 0.0001f)
    }

    @Test
    fun `copyWith preserves and overrides partialPages`() {
        val a = ProgressSnapshot("x", 10L, 1, 1, 10, 5000L, partialPages = 0.5f)
        assertEquals(0.5f, a.copyWith(speed = 20L).partialPages, 0.0001f)
        assertEquals(0.75f, a.copyWith(partialPages = 0.75f).partialPages, 0.0001f)
    }

    @Test
    fun `barProgress combines finished pages and partial fraction`() {
        // 3 finished of 10 + 0.5 in flight → 3500 of 10000
        val s = ProgressSnapshot("x", finished = 3, total = 10, partialPages = 0.5f)
        assertEquals(10_000, s.barMax())
        assertEquals(3_500, s.barProgress())
    }

    @Test
    fun `barProgress clamps overshoot to barMax`() {
        // A stale partial can briefly double-count a just-finished page.
        val s = ProgressSnapshot("x", finished = 10, total = 10, partialPages = 0.9f)
        assertEquals(10_000, s.barProgress())
    }

    @Test
    fun `barProgress guards non-positive total and negative partial`() {
        assertEquals(0, ProgressSnapshot("x", total = -1, partialPages = 0.5f).barProgress())
        assertEquals(1, ProgressSnapshot("x", total = -1).barMax())
        val s = ProgressSnapshot("x", finished = 2, total = 10, partialPages = -1f)
        assertEquals(2_000, s.barProgress())
    }
}
