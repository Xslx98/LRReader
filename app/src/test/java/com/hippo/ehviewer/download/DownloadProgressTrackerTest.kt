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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadProgressTrackerTest {

    @Test
    fun `update creates snapshot when key is absent`() {
        val tracker = DownloadProgressTracker()
        tracker.update("abc", speed = 100L)
        val snap = tracker.snapshot("abc")
        assertEquals(100L, snap?.speed)
        assertEquals(0, snap?.finished) // default
    }

    @Test
    fun `update merges fields into existing snapshot`() {
        val tracker = DownloadProgressTracker()
        tracker.update("abc", speed = 100L, total = 10)
        tracker.update("abc", finished = 3, downloaded = 3)
        val snap = tracker.snapshot("abc")!!
        assertEquals(100L, snap.speed)
        assertEquals(10, snap.total)
        assertEquals(3, snap.finished)
        assertEquals(3, snap.downloaded)
    }

    @Test
    fun `clear removes the entry`() {
        val tracker = DownloadProgressTracker()
        tracker.update("abc", speed = 100L)
        tracker.clear("abc")
        assertNull(tracker.snapshot("abc"))
    }

    @Test
    fun `resetForStart seeds the start-of-download sentinel values`() {
        val tracker = DownloadProgressTracker()
        tracker.update("abc", speed = 500L, finished = 10, downloaded = 10, total = 20)
        tracker.resetForStart("abc")
        val snap = tracker.snapshot("abc")!!
        assertEquals(-1L, snap.speed)
        assertEquals(-1L, snap.remaining)
        assertEquals(0, snap.finished)
        assertEquals(0, snap.downloaded)
        assertEquals(-1, snap.total)
    }

    @Test
    fun `clearAll empties the map`() {
        val tracker = DownloadProgressTracker()
        tracker.update("a", speed = 1L)
        tracker.update("b", speed = 2L)
        tracker.clearAll()
        assertNull(tracker.snapshot("a"))
        assertNull(tracker.snapshot("b"))
    }

    @Test
    fun `flow emits on every update`() = runTest(UnconfinedTestDispatcher()) {
        val tracker = DownloadProgressTracker()
        val emissions = mutableListOf<Map<String, ProgressSnapshot>>()
        val job = launch { tracker.progressFlow.collect { emissions.add(it) } }
        tracker.update("abc", speed = 10L)
        tracker.update("abc", speed = 20L)
        tracker.clear("abc")
        yield()
        job.cancel()
        // initial empty emission + 3 changes
        assert(emissions.size >= 4) { "expected >=4 emissions, got ${emissions.size}: $emissions" }
    }
}
