package com.lanraragi.reader.client.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for pattern failure lockout logic in [LRRAuthManager].
 *
 * Uses a controllable clock source to deterministically test time-based lockout
 * without relying on real wall-clock time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PatternLockoutTest {

    private lateinit var ctx: Context
    private var fakeTimeMs: Long = 1_000_000L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        LRRAuthManager.initialize(ctx)
        LRRAuthManager.initializeForTesting(
            ctx.getSharedPreferences("lockout_test", Context.MODE_PRIVATE)
        )
        // Install a fake clock
        fakeTimeMs = 1_000_000L
        LRRAuthManager.clockMillis = { fakeTimeMs }
    }

    @After
    fun tearDown() {
        LRRAuthManager.clear()
        LRRAuthManager.clockMillis = { System.currentTimeMillis() }
    }

    // ── isLockedOut ─────────────────────────────────────────────────

    @Test
    fun isLockedOut_falseInitially() {
        assertFalse(LRRAuthManager.isLockedOut())
    }

    @Test
    fun isLockedOut_falseAfterFewFailures() {
        repeat(4) { LRRAuthManager.recordFailure() }
        assertFalse("Should not be locked out after 4 failures", LRRAuthManager.isLockedOut())
    }

    @Test
    fun isLockedOut_trueAfterFiveFailures() {
        repeat(5) { LRRAuthManager.recordFailure() }
        assertTrue("Should be locked out after 5 failures", LRRAuthManager.isLockedOut())
    }

    @Test
    fun isLockedOut_expiresAfterThirtySeconds() {
        repeat(5) { LRRAuthManager.recordFailure() }
        assertTrue(LRRAuthManager.isLockedOut())
        // Advance clock past the 30-second lockout
        fakeTimeMs += 30_001L
        assertFalse("Lockout should expire after 30s", LRRAuthManager.isLockedOut())
    }

    @Test
    fun isLockedOut_trueAfterTenFailures() {
        repeat(10) { LRRAuthManager.recordFailure() }
        assertTrue("Should be locked out after 10 failures", LRRAuthManager.isLockedOut())
    }

    @Test
    fun isLockedOut_fiveMinuteLockoutAfterTenFailures() {
        repeat(10) { LRRAuthManager.recordFailure() }
        assertTrue(LRRAuthManager.isLockedOut())
        // 30 seconds is not enough for the 5-minute lockout
        fakeTimeMs += 30_001L
        assertTrue("Should still be locked out (5min lockout)", LRRAuthManager.isLockedOut())
        // Advance past 5 minutes
        fakeTimeMs += 270_000L // total: ~300s
        assertFalse("Lockout should expire after 5min", LRRAuthManager.isLockedOut())
    }

    // ── getLockoutRemainingMs ────────────────────────────────────────

    @Test
    fun getLockoutRemainingMs_zeroWhenNotLockedOut() {
        assertEquals(0L, LRRAuthManager.getLockoutRemainingMs())
    }

    @Test
    fun getLockoutRemainingMs_thirtySecondsAfterFiveFailures() {
        repeat(5) { LRRAuthManager.recordFailure() }
        val remaining = LRRAuthManager.getLockoutRemainingMs()
        assertTrue("Remaining should be close to 30s", remaining in 29_000L..30_000L)
    }

    @Test
    fun getLockoutRemainingMs_fiveMinutesAfterTenFailures() {
        repeat(10) { LRRAuthManager.recordFailure() }
        val remaining = LRRAuthManager.getLockoutRemainingMs()
        assertTrue("Remaining should be close to 5min", remaining in 299_000L..300_000L)
    }

    @Test
    fun getLockoutRemainingMs_zeroAfterExpiry() {
        repeat(5) { LRRAuthManager.recordFailure() }
        fakeTimeMs += 31_000L
        assertEquals(0L, LRRAuthManager.getLockoutRemainingMs())
    }

    // ── resetFailures ───────────────────────────────────────────────

    @Test
    fun resetFailures_clearsLockout() {
        repeat(5) { LRRAuthManager.recordFailure() }
        assertTrue(LRRAuthManager.isLockedOut())
        LRRAuthManager.resetFailures()
        assertFalse("Lockout should be cleared after reset", LRRAuthManager.isLockedOut())
    }

    @Test
    fun resetFailures_resetsCounter() {
        repeat(5) { LRRAuthManager.recordFailure() }
        LRRAuthManager.resetFailures()
        assertEquals(0, LRRAuthManager.getFailureCount())
    }

    // ── getFailureCount ─────────────────────────────────────────────

    @Test
    fun getFailureCount_zeroInitially() {
        assertEquals(0, LRRAuthManager.getFailureCount())
    }

    @Test
    fun getFailureCount_incrementsOnEachFailure() {
        LRRAuthManager.recordFailure()
        assertEquals(1, LRRAuthManager.getFailureCount())
        LRRAuthManager.recordFailure()
        assertEquals(2, LRRAuthManager.getFailureCount())
        LRRAuthManager.recordFailure()
        assertEquals(3, LRRAuthManager.getFailureCount())
    }

    // ── Integration with verifyPattern ──────────────────────────────

    @Test
    fun verifyPattern_returnsFalseWhenLockedOut() {
        LRRAuthManager.setPattern("test123")
        repeat(5) { LRRAuthManager.recordFailure() }
        // Even correct pattern should be rejected during lockout
        assertFalse("Verify should return false when locked out",
            LRRAuthManager.verifyPattern("test123"))
    }

    @Test
    fun verifyPattern_resetsFailuresOnSuccess() {
        LRRAuthManager.setPattern("test123")
        repeat(3) { LRRAuthManager.recordFailure() }
        assertEquals(3, LRRAuthManager.getFailureCount())
        assertTrue(LRRAuthManager.verifyPattern("test123"))
        assertEquals(0, LRRAuthManager.getFailureCount())
    }

    @Test
    fun verifyPattern_recordsFailureOnWrongInput() {
        LRRAuthManager.setPattern("test123")
        LRRAuthManager.verifyPattern("wrong")
        assertEquals(1, LRRAuthManager.getFailureCount())
    }

    @Test
    fun verifyPattern_triggersLockoutAfterFiveWrongAttempts() {
        LRRAuthManager.setPattern("test123")
        repeat(5) { LRRAuthManager.verifyPattern("wrong") }
        assertTrue("Should be locked out after 5 wrong verify attempts",
            LRRAuthManager.isLockedOut())
        assertEquals(5, LRRAuthManager.getFailureCount())
    }

    // ── Persistence across simulated restart ────────────────────────

    @Test
    fun lockoutSurvivesStorageUnavailability() {
        // Record failures, then simulate KeyStore going away (but plain prefs survive)
        repeat(5) { LRRAuthManager.recordFailure() }
        assertTrue(LRRAuthManager.isLockedOut())
        // Simulate KeyStore failure — plain prefs (lockout state) survive
        LRRAuthManager.simulateStorageUnavailableForTesting()
        assertTrue("Lockout should survive KeyStore failure", LRRAuthManager.isLockedOut())
    }

    // ── Edge: continued failures extend lockout ─────────────────────

    @Test
    fun continuedFailuresUpgradeLockout() {
        // 5 failures -> 30s lockout
        repeat(5) { LRRAuthManager.recordFailure() }
        val remaining5 = LRRAuthManager.getLockoutRemainingMs()
        assertTrue(remaining5 <= 30_000L)

        // Wait for lockout to expire
        fakeTimeMs += 31_000L
        assertFalse(LRRAuthManager.isLockedOut())

        // 5 more failures (total 10) -> 5min lockout
        repeat(5) { LRRAuthManager.recordFailure() }
        val remaining10 = LRRAuthManager.getLockoutRemainingMs()
        assertTrue("After 10 failures, lockout should be ~5min",
            remaining10 > 30_000L)
    }
}
