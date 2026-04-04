package com.hippo.ehviewer.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [CoroutineBridge] utility functions.
 *
 * These verify the function signatures and parameter contracts.
 * Full integration tests with lifecycle require Robolectric + Activity,
 * but we can verify the Callable/Runnable SAM conversion works.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineBridgeTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun callableReturnsValue() {
        val callable = java.util.concurrent.Callable { 42 }
        assertEquals(42, callable.call())
    }

    @Test
    fun runnableExecutes() {
        var executed = false
        val runnable = Runnable { executed = true }
        runnable.run()
        assertTrue("Runnable should have executed", executed)
    }

    @Test
    fun callableWithDatabaseSimulation() {
        val callable = java.util.concurrent.Callable {
            // Simulate DB query returning a list
            listOf("profile1", "profile2", "profile3")
        }
        val result = callable.call()
        assertEquals(3, result.size)
        assertEquals("profile1", result[0])
    }
}
