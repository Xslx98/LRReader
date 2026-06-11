package com.hippo.ehviewer.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkWaitBudgetTest {

    @Test
    fun fromMinutes_zeroMeansNever() {
        assertEquals(Long.MAX_VALUE, NetworkWaitBudget.fromMinutes(0) { 0L }.totalBudgetMillis)
    }

    @Test
    fun fromMinutes_convertsToMillis() {
        assertEquals(300_000L, NetworkWaitBudget.fromMinutes(5) { 0L }.totalBudgetMillis)
    }

    @Test
    fun remainingMillis_countsFromFirstWait() {
        val budget = NetworkWaitBudget(totalBudgetMillis = 300_000L) { 0L }
        budget.markWaitStartIfNeeded(1_000L)
        assertEquals(300_000L, budget.remainingMillis(1_000L))
        assertEquals(200_000L, budget.remainingMillis(101_000L))
        assertEquals(0L, budget.remainingMillis(1_000L + 400_000L))
    }

    @Test
    fun awaitNetworkOrExpire_returnsTrueImmediatelyWhenAvailable() = runTest {
        val flow = MutableStateFlow(true)
        val budget = NetworkWaitBudget(totalBudgetMillis = 10_000L) { 0L }
        assertTrue(budget.awaitNetworkOrExpire(flow))
    }

    @Test
    fun awaitNetworkOrExpire_returnsFalseWhenBudgetExhausted() = runTest {
        val flow = MutableStateFlow(false)
        // Fixed clock at 1_000 ms; outage "started" at 0, so a 100 ms budget is already
        // exhausted -> returns false without suspending (deterministic).
        val budget = NetworkWaitBudget(totalBudgetMillis = 100L) { 1_000L }
        budget.markWaitStartIfNeeded(0L)
        assertFalse(budget.awaitNetworkOrExpire(flow))
    }

    @Test
    fun awaitNetworkOrExpire_resetsBudgetAfterNetworkReturns() = runTest {
        var now = 0L
        val flow = MutableStateFlow(false)
        val budget = NetworkWaitBudget(totalBudgetMillis = 5_000L) { now }

        // First outage at t=0: the network returns quickly, so the wait succeeds.
        val first = async { budget.awaitNetworkOrExpire(flow) }
        runCurrent()
        flow.value = true
        assertTrue(first.await())

        // Long time passes while ONLINE — this must not count against the budget.
        now = 100_000L
        flow.value = false

        // A new outage gets the full budget again, not a budget already exhausted by the
        // online interval since the first wait.
        budget.markWaitStartIfNeeded(now)
        assertEquals(5_000L, budget.remainingMillis(now))
    }

    @Test
    fun awaitNetworkOrExpire_returnsTrueWhenNetworkReturnsInTime() = runTest {
        val flow = MutableStateFlow(false)
        // Injected clock fixed at 0 -> full budget remains; withTimeoutOrNull runs on the
        // test scheduler's virtual time. runCurrent() lets the awaiter suspend at
        // first { it }, then the flow flip resumes it -- no real waiting.
        val budget = NetworkWaitBudget(totalBudgetMillis = 5_000L) { 0L }
        val result = async { budget.awaitNetworkOrExpire(flow) }
        runCurrent()
        flow.value = true
        assertTrue(result.await())
    }
}
