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
import com.lanraragi.reader.server.ServerSession
import com.lanraragi.reader.security.AppLockCredentials
import com.lanraragi.reader.server.SecureCredentialStore

/**
 * Tests for the INF-9 async-init readiness gate.
 *
 * [SecureCredentialStore] is a process-wide singleton whose state survives across test
 * classes inside one Robolectric sandbox, so every test here restores the gate
 * to the never-scheduled default in BOTH setUp and tearDown — leaving it
 * "scheduled but pending" would hang any later test that touches auth state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SecureCredentialStoreInitGateTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SecureCredentialStore.resetInitGateForTesting()
    }

    @After
    fun tearDown() {
        SecureCredentialStore.clear()
        SecureCredentialStore.resetInitGateForTesting()
    }

    /** Legacy semantics: tests that never initialize read defaults, no blocking. */
    @Test
    fun neverScheduled_gettersReturnDefaultsImmediately() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        assertNull(ServerSession.getServerUrl())
        assertFalse(ServerSession.isConfigured())
        assertEquals(0L, ServerSession.getActiveProfileId())
        assertFalse(AppLockCredentials.hasPattern())
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
            SecureCredentialStore.scheduleInitialize(ctx, scope)

            val readerDone = CountDownLatch(1)
            var observedUrl: String? = "sentinel"
            thread(isDaemon = true) {
                observedUrl = ServerSession.getServerUrl()
                readerDone.countDown()
            }

            assertFalse(
                "reader must block while scheduled init is pending",
                readerDone.await(300, TimeUnit.MILLISECONDS)
            )

            // Open the gate with injected prefs carrying a known value.
            val prefs = ctx.getSharedPreferences("lrr_gate_test", Context.MODE_PRIVATE)
            prefs.edit().putString("server_url", "http://10.0.0.9:3000").commit()
            SecureCredentialStore.initializeForTesting(prefs)

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
            SecureCredentialStore.scheduleInitialize(ctx, scope)
            scheduler.advanceUntilIdle() // runs initialize(ctx) on the test thread

            // Gate must be open: a reader thread completes promptly.
            val readerDone = CountDownLatch(1)
            thread(isDaemon = true) {
                ServerSession.getServerUrl()
                AppLockCredentials.isLockedOut()
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

    /**
     * Audit 2026-10-04 C34 / STAB-15: a hung init no longer blocks readers
     * forever. The first reader gives up after the timeout and sees defaults;
     * later readers do not wait again; the gate still opens normally afterwards.
     */
    @Test(timeout = 20_000)
    fun hungInit_readersGiveUpAfterTimeout_thenSeeValueOnceGateOpens() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(
            StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, t -> println("contained: $t") }
        )
        try {
            SecureCredentialStore.scheduleInitialize(ctx, scope) // never advanced: init "hangs"
            SecureCredentialStore.initTimeoutMs = 200

            val first = CountDownLatch(1)
            var observed: String? = "sentinel"
            thread(isDaemon = true) {
                observed = ServerSession.getServerUrl()
                first.countDown()
            }
            assertTrue("reader must give up after the timeout", first.await(5, TimeUnit.SECONDS))
            assertNull(observed)

            val started = System.nanoTime()
            ServerSession.getServerUrl()
            assertTrue(
                "later readers must not wait again",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 150
            )

            val prefs = ctx.getSharedPreferences("lrr_gate_timeout_test", Context.MODE_PRIVATE)
            prefs.edit().putString("server_url", "http://10.0.0.8:3000").commit()
            SecureCredentialStore.initializeForTesting(prefs)
            assertEquals("http://10.0.0.8:3000", ServerSession.getServerUrl())
        } finally {
            scope.cancel()
        }
    }

    /**
     * Audit 2026-10-06 N5: the main thread (Robolectric runs tests on it) gives
     * up before the 5 s input-dispatch ANR limit, with the production bounds,
     * while a background reader keeps waiting and still gets the real value.
     */
    @Test(timeout = 20_000)
    fun hungInit_mainThreadGivesUpBeforeAnrLimit_backgroundReaderKeepsWaiting() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(
            StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, t -> println("contained: $t") }
        )
        try {
            SecureCredentialStore.scheduleInitialize(ctx, scope) // never advanced: init "hangs"
            assertTrue(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())

            val started = System.nanoTime()
            assertNull(ServerSession.getServerUrl())
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("main thread waited $waitedMs ms, at the ANR limit", waitedMs < 5_000)

            val again = System.nanoTime()
            ServerSession.getActiveProfileId()
            assertTrue(
                "later main-thread readers must not wait again",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - again) < 150
            )

            val background = CountDownLatch(1)
            var observed: String? = "sentinel"
            thread(isDaemon = true) {
                observed = ServerSession.getServerUrl()
                background.countDown()
            }
            assertFalse(
                "a background reader keeps its longer wait",
                background.await(300, TimeUnit.MILLISECONDS)
            )
            val prefs = ctx.getSharedPreferences("lrr_gate_main_test", Context.MODE_PRIVATE)
            prefs.edit().putString("server_url", "http://10.0.0.7:3000").commit()
            SecureCredentialStore.initializeForTesting(prefs)
            assertTrue(background.await(5, TimeUnit.SECONDS))
            assertEquals("http://10.0.0.7:3000", observed)
        } finally {
            scope.cancel()
        }
    }
}
