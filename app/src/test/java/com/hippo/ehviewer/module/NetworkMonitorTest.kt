package com.hippo.ehviewer.module

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NetworkMonitorTest {

    private lateinit var monitor: NetworkMonitor

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        monitor = NetworkMonitor(ctx)
        // Force a known-unavailable baseline regardless of Robolectric's default network.
        while (monitor.isAvailable) monitor.handleLost()
    }

    @Test
    fun handleAvailable_thenLost_updatesFlagAndFlow() {
        monitor.handleAvailable()
        assertTrue(monitor.isAvailable)
        assertTrue(monitor.isAvailableFlow.value)

        monitor.handleLost()
        assertFalse(monitor.isAvailable)
        assertFalse(monitor.isAvailableFlow.value)
    }

    @Test
    fun awaitAvailable_resumesAfterHandleAvailable() = runTest {
        val resumed = AtomicBoolean(false)
        // UnconfinedTestDispatcher runs the launched coroutine eagerly until it
        // suspends inside awaitAvailable() (flow is false), so `resumed` stays false.
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            monitor.awaitAvailable()
            resumed.set(true)
        }
        assertFalse("Should still be waiting while unavailable", resumed.get())
        monitor.handleAvailable()
        job.join()
        assertTrue("Should resume once available", resumed.get())
    }
}
