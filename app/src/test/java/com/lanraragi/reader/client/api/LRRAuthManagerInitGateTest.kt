package com.lanraragi.reader.client.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the INF-9 async-init readiness gate.
 *
 * [LRRAuthManager] is a process-wide singleton whose state survives across test
 * classes inside one Robolectric sandbox, so every test here restores the gate
 * to the never-scheduled default in BOTH setUp and tearDown — leaving it
 * "scheduled but pending" would hang any later test that touches auth state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LRRAuthManagerInitGateTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        LRRAuthManager.resetInitGateForTesting()
    }

    @After
    fun tearDown() {
        LRRAuthManager.clear()
        LRRAuthManager.resetInitGateForTesting()
    }

    /** Legacy semantics: tests that never initialize read defaults, no blocking. */
    @Test
    fun neverScheduled_gettersReturnDefaultsImmediately() {
        LRRAuthManager.simulateStorageUnavailableForTesting()
        assertNull(LRRAuthManager.getServerUrl())
        assertFalse(LRRAuthManager.isConfigured())
        assertEquals(0L, LRRAuthManager.getActiveProfileId())
        assertFalse(LRRAuthManager.hasPattern())
    }

    /**
     * The load-bearing property: scheduleInitialize sets the "scheduled" flag
     * SYNCHRONOUSLY, so a reader arriving before the init coroutine even starts
     * blocks instead of falling back to defaults — then proceeds once the gate
     * opens with the initialized value visible.
     */
    @Test
    fun scheduled_readerBlocksUntilGateOpens_thenSeesInitializedValue() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(
            StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, t -> println("contained: $t") }
        )
        try {
            // Dispatcher is never advanced: initialize() has NOT run.
            LRRAuthManager.scheduleInitialize(ctx, scope)

            val readerDone = CountDownLatch(1)
            var observedUrl: String? = "sentinel"
            thread(isDaemon = true) {
                observedUrl = LRRAuthManager.getServerUrl()
                readerDone.countDown()
            }

            assertFalse(
                "reader must block while scheduled init is pending",
                readerDone.await(300, TimeUnit.MILLISECONDS)
            )

            // Open the gate with injected prefs carrying a known value.
            val prefs = ctx.getSharedPreferences("lrr_gate_test", Context.MODE_PRIVATE)
            prefs.edit().putString("server_url", "http://10.0.0.9:3000").commit()
            LRRAuthManager.initializeForTesting(prefs)

            assertTrue(
                "reader must be released once the gate opens",
                readerDone.await(5, TimeUnit.SECONDS)
            )
            assertEquals("http://10.0.0.9:3000", observedUrl)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Production initialize() under Robolectric takes the KeyStore-failure catch
     * branch (sPrefs = null). The finally-block must still open the gate so
     * readers see the degraded-but-defined semantics instead of hanging.
     */
    @Test
    fun productionInitializeFailureBranch_stillOpensGate() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(
            StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, t -> println("contained: $t") }
        )
        try {
            LRRAuthManager.scheduleInitialize(ctx, scope)
            scheduler.advanceUntilIdle() // runs initialize(ctx) on the test thread

            // Gate must be open: a reader thread completes promptly.
            val readerDone = CountDownLatch(1)
            thread(isDaemon = true) {
                LRRAuthManager.getServerUrl()
                LRRAuthManager.isLockedOut()
                readerDone.countDown()
            }
            assertTrue(
                "gate must be open after initialize() finished (even on failure branch)",
                readerDone.await(5, TimeUnit.SECONDS)
            )
        } finally {
            scope.cancel()
        }
    }
}
