/*
 * Copyright 2026 LRReader contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.client

import com.hippo.ehviewer.dao.Filter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [EhFilter.awaitReady] — the W1-3 readiness gate that blocks consumers
 * until [EhFilter.loadFromDb] completes (or until a timeout elapses, in which case
 * the gate returns pass-through so the gallery list still renders).
 *
 * Design note: [EhFilter] is a singleton with an `internal` constructor (now
 * `@VisibleForTesting`). Tests instantiate a fresh `EhFilter()` directly via the
 * internal constructor and bypass [EhFilter.getInstance] to avoid singleton state
 * pollution between tests. We do NOT touch the real Room database — instead we
 * inject a fake `loadFromDb`-equivalent body that simulates the suspend timing
 * via `delay()` on the virtual test dispatcher.
 */
class EhFilterReadyGateTest {

    private lateinit var ehFilter: EhFilter

    @Before
    fun setUp() {
        // Reset the singleton field so this test does not pollute / get polluted
        // by other tests in the same JVM (notably EhFilterTest, which also reflects).
        val sInstanceField = EhFilter::class.java.getDeclaredField("sInstance")
        sInstanceField.isAccessible = true
        sInstanceField.set(null, null)

        // Use the @VisibleForTesting internal constructor directly. Reflection is
        // unnecessary now that the constructor is no longer `private`.
        ehFilter = EhFilter()
    }

    /**
     * Replaces the private `readyDeferred` field with the given deferred so the
     * test can simulate the loadFromDb completion lifecycle without depending on
     * a real Room database.
     */
    private fun installDeferred(deferred: CompletableDeferred<Unit>) {
        val field = EhFilter::class.java.getDeclaredField("readyDeferred")
        field.isAccessible = true
        field.set(ehFilter, deferred)
    }

    /**
     * Simulates loadFromDb's behaviour: delay for [loadDurationMs] then populate
     * filter lists and complete the readiness deferred. Mirrors the real
     * loadFromDb's try/finally guarantee.
     */
    private suspend fun simulateLoadFromDb(
        loadDurationMs: Long,
        filtersToInject: List<Filter> = emptyList(),
        deferred: CompletableDeferred<Unit>,
    ) {
        try {
            delay(loadDurationMs)
            ehFilter.loadFromList(filtersToInject) // synchronously populates lists
        } finally {
            deferred.complete(Unit)
        }
    }

    // ---- Test 1: fast load — awaitReady waits and gets a non-empty filter list ----

    @Test
    fun awaitReady_fastLoad_completesWithFilters() = runTest {
        val deferred = CompletableDeferred<Unit>()
        installDeferred(deferred)

        val filters = listOf(
            Filter(mode = EhFilter.MODE_TITLE, text = "blocked", enable = true)
        )

        // Launch a "loader" coroutine that takes ~500ms of virtual time.
        val loadJob = launch {
            simulateLoadFromDb(loadDurationMs = 500L, filtersToInject = filters, deferred = deferred)
        }

        // Consumer coroutine awaits readiness with a 1000ms budget.
        val consumerResult = async {
            ehFilter.awaitReady(timeoutMs = 1000L)
            ehFilter.titleFilterList.size
        }

        // Drive virtual time forward — both coroutines should complete by now.
        advanceUntilIdle()

        assertTrue("Loader should have finished", loadJob.isCompleted)
        assertTrue("Readiness deferred should be completed", ehFilter.isReady)
        assertEquals(
            "Consumer should observe 1 filter after awaitReady returned",
            1,
            consumerResult.await(),
        )
    }

    // ---- Test 2: timeout — awaitReady returns within timeoutMs WITHOUT throwing ----

    @Test
    fun awaitReady_timeout_returnsWithoutThrowingAndListsRemainEmpty() = runTest {
        val deferred = CompletableDeferred<Unit>()
        installDeferred(deferred)

        // Loader takes 2000ms — longer than the consumer's 1000ms budget.
        val loadJob = launch {
            simulateLoadFromDb(
                loadDurationMs = 2000L,
                filtersToInject = listOf(
                    Filter(mode = EhFilter.MODE_TITLE, text = "x", enable = true)
                ),
                deferred = deferred,
            )
        }

        var threw = false
        var consumerSawReady = true
        val consumerJob = launch {
            try {
                ehFilter.awaitReady(timeoutMs = 1000L)
            } catch (_: Throwable) {
                threw = true
            }
            consumerSawReady = ehFilter.isReady
        }

        // Advance only 1100ms — past the consumer's timeout but before the loader finishes.
        advanceTimeBy(1100L)
        runCurrent()

        assertTrue("Consumer should have returned by 1100ms", consumerJob.isCompleted)
        assertFalse("awaitReady must not throw on timeout", threw)
        assertFalse(
            "Filters should NOT be loaded yet at 1100ms (loader needs 2000ms)",
            consumerSawReady,
        )
        assertTrue(
            "Title filter list should still be empty after timeout",
            ehFilter.titleFilterList.isEmpty(),
        )

        // Now let the loader finish so we leave a clean state for runTest.
        advanceUntilIdle()
        assertTrue(loadJob.isCompleted)
        assertTrue("Loader eventually completes the deferred", ehFilter.isReady)
    }

    // ---- Test 3: idempotent — awaitReady after completion returns immediately ----

    @Test
    fun awaitReady_afterAlreadyComplete_returnsImmediately() = runTest {
        // Pre-populate filters via loadFromList, which completes the deferred synchronously.
        ehFilter.loadFromList(
            listOf(Filter(mode = EhFilter.MODE_TAG, text = "preloaded", enable = true))
        )
        assertTrue("Filter must be ready after loadFromList", ehFilter.isReady)

        val startTime = currentTime
        // Use a tiny non-zero timeout to prove this returns without consuming budget.
        ehFilter.awaitReady(timeoutMs = 50L)
        val elapsed = currentTime - startTime

        assertEquals(
            "awaitReady should return immediately (0ms virtual time) when already ready",
            0L,
            elapsed,
        )
        assertEquals(1, ehFilter.tagFilterList.size)
    }

}
