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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingProgressSyncerTest {

    @Test
    fun `conflates a burst to the in-flight page plus the latest`() = runTest {
        val sent = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val syncer = ReadingProgressSyncer(this) { page ->
            sent.add(page)
            if (sent.size == 1) gate.await()
        }
        syncer.submit(1)
        runCurrent() // first send is now suspended on the gate
        syncer.submit(2)
        syncer.submit(3)
        syncer.submit(4)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1, 4), sent)
    }

    @Test
    fun `sends are strictly serialized`() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        val syncer = ReadingProgressSyncer(this) { _ ->
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            kotlinx.coroutines.delay(10)
            inFlight--
        }
        repeat(20) { syncer.submit(it) }
        advanceUntilIdle()
        assertTrue("never more than one PUT in flight", maxInFlight <= 1)
    }

    @Test
    fun `a failed send does not kill subsequent syncs`() = runTest {
        val sent = mutableListOf<Int>()
        var failFirst = true
        val syncer = ReadingProgressSyncer(this) { page ->
            if (failFirst) {
                failFirst = false
                throw IOException("boom")
            }
            sent.add(page)
        }
        syncer.submit(5)
        advanceUntilIdle()
        syncer.submit(6)
        advanceUntilIdle()
        assertEquals(listOf(6), sent)
    }

    @Test
    fun `negative pages are ignored`() = runTest {
        val sent = mutableListOf<Int>()
        val syncer = ReadingProgressSyncer(this) { page -> sent.add(page) }
        syncer.submit(-1)
        advanceUntilIdle()
        assertEquals(emptyList<Int>(), sent)
    }

    @Test
    fun `page zero is a valid first page`() = runTest {
        // Guard is page0 < 0, so 0 (server page 1) must NOT be dropped.
        val sent = mutableListOf<Int>()
        val syncer = ReadingProgressSyncer(this) { page -> sent.add(page) }
        syncer.submit(0)
        advanceUntilIdle()
        assertEquals(listOf(0), sent)
    }

    @Test
    fun `submit after the drain goes idle relaunches a fresh drain`() = runTest {
        // The common real-world path: one page turn syncs, the scope goes
        // idle, the next page turn must relaunch the drain.
        val sent = mutableListOf<Int>()
        val syncer = ReadingProgressSyncer(this) { page -> sent.add(page) }
        syncer.submit(1)
        advanceUntilIdle()
        syncer.submit(2)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), sent)
    }
}
