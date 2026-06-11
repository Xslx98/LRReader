package com.hippo.ehviewer.module

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import org.robolectric.shadows.ShadowNetwork
import java.util.concurrent.atomic.AtomicBoolean

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NetworkMonitorTest {

    private lateinit var monitor: NetworkMonitor

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        monitor = NetworkMonitor(ctx)
        // Clear whatever the monitor may have seeded from Robolectric's default active
        // network so each test starts from a known-empty baseline.
        cm.activeNetwork?.let { monitor.handleLost(it) }
    }

    private fun network(netId: Int): Network = ShadowNetwork.newInstance(netId)

    @Test
    fun handleAvailable_thenLost_updatesFlagAndFlow() {
        val net = network(1)
        monitor.handleAvailable(net)
        assertTrue(monitor.isAvailable)
        assertTrue(monitor.isAvailableFlow.value)

        monitor.handleLost(net)
        assertFalse(monitor.isAvailable)
        assertFalse(monitor.isAvailableFlow.value)
    }

    @Test
    fun duplicateAvailableForSameNetwork_clearedBySingleLost() {
        // Reproduces the seed + immediate-registration-callback double count:
        // the SAME network is reported available twice, then lost once. A counter
        // would be left at 1 (stuck available); identity tracking clears it.
        val net = network(7)
        monitor.handleAvailable(net) // seed (from activeNetwork)
        monitor.handleAvailable(net) // duplicate delivered by registerNetworkCallback
        assertTrue(monitor.isAvailable)

        monitor.handleLost(net)
        assertFalse("Same network must count once; one loss clears it", monitor.isAvailable)
        assertFalse(monitor.isAvailableFlow.value)
    }

    @Test
    fun twoDistinctNetworks_needTwoLosses() {
        val wifi = network(1)
        val cell = network(2)
        monitor.handleAvailable(wifi)
        monitor.handleAvailable(cell)

        monitor.handleLost(wifi)
        assertTrue("Still one network up", monitor.isAvailable)

        monitor.handleLost(cell)
        assertFalse(monitor.isAvailable)
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
        monitor.handleAvailable(network(1))
        job.join()
        assertTrue("Should resume once available", resumed.get())
    }
}
