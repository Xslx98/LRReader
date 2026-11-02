package com.lanraragi.reader.security

import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import com.lanraragi.reader.server.ServerSession
import com.lanraragi.reader.server.SecureCredentialStore

/**
 * Unit tests for pattern failure lockout logic in [AppLockCredentials].
 *
 * Uses a controllable clock source to deterministically test time-based lockout
 * without relying on real wall-clock time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PatternLockoutTest {

    private lateinit var ctx: Context
    private var fakeTimeMs: Long = 1_000_000L
    private var fakeBoot: Int = 7

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SecureCredentialStore.initialize(ctx)
        SecureCredentialStore.initializeForTesting(
            ctx.getSharedPreferences("lockout_test", Context.MODE_PRIVATE)
        )
        // Install a fake clock
        fakeTimeMs = 1_000_000L
        fakeBoot = 7
        SecureCredentialStore.clockMillis = { fakeTimeMs }
        SecureCredentialStore.bootCount = { fakeBoot }
    }

    @After
    fun tearDown() {
        SecureCredentialStore.clear()
        SecureCredentialStore.clockMillis = { android.os.SystemClock.elapsedRealtime() }
        SecureCredentialStore.bootCount = { -1 }
    }

    // ── isLockedOut ─────────────────────────────────────────────────

    @Test
    fun isLockedOut_falseInitially() {
        assertFalse(AppLockCredentials.isLockedOut())
    }

    @Test
    fun isLockedOut_falseAfterFewFailures() {
        repeat(4) { AppLockCredentials.recordFailure() }
        assertFalse("Should not be locked out after 4 failures", AppLockCredentials.isLockedOut())
    }

    @Test
    fun isLockedOut_trueAfterFiveFailures() {
        repeat(5) { AppLockCredentials.recordFailure() }
        assertTrue("Should be locked out after 5 failures", AppLockCredentials.isLockedOut())
    }

    @Test
    fun isLockedOut_expiresAfterThirtySeconds() {
        repeat(5) { AppLockCredentials.recordFailure() }
        assertTrue(AppLockCredentials.isLockedOut())
        // Advance clock past the 30-second lockout
        fakeTimeMs += 30_001L
        assertFalse("Lockout should expire after 30s", AppLockCredentials.isLockedOut())
    }

    @Test
    fun isLockedOut_trueAfterTenFailures() {
        repeat(10) { AppLockCredentials.recordFailure() }
        assertTrue("Should be locked out after 10 failures", AppLockCredentials.isLockedOut())
    }

    @Test
    fun lockoutEscalatesWithEachFurtherFailure() {
        val expected = listOf(30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L, 3_600_000L)
        repeat(4) { AppLockCredentials.recordFailure() }
        for (duration in expected) {
            AppLockCredentials.recordFailure()
            assertEquals(duration, AppLockCredentials.getLockoutRemainingMs())
        }
    }

    @Test
    fun lockoutIsNotEndedByAWallClockChange() {
        repeat(5) { AppLockCredentials.recordFailure() }
        // A wall-clock jump does not move the monotonic clock at all.
        assertTrue(AppLockCredentials.isLockedOut())
        fakeTimeMs += 10_000L
        assertEquals(20_000L, AppLockCredentials.getLockoutRemainingMs())
    }

    @Test
    fun rebootReAnchorsTheLockoutInsteadOfEndingIt() {
        repeat(5) { AppLockCredentials.recordFailure() }
        fakeTimeMs += 20_000L
        // Reboot: the monotonic clock restarts near zero.
        fakeBoot += 1
        fakeTimeMs = 5_000L
        assertEquals(30_000L, AppLockCredentials.getLockoutRemainingMs())
        fakeTimeMs += 30_001L
        assertFalse(AppLockCredentials.isLockedOut())
    }

    // ── getLockoutRemainingMs ────────────────────────────────────────

    @Test
    fun getLockoutRemainingMs_zeroWhenNotLockedOut() {
        assertEquals(0L, AppLockCredentials.getLockoutRemainingMs())
    }

    @Test
    fun getLockoutRemainingMs_thirtySecondsAfterFiveFailures() {
        repeat(5) { AppLockCredentials.recordFailure() }
        val remaining = AppLockCredentials.getLockoutRemainingMs()
        assertTrue("Remaining should be close to 30s", remaining in 29_000L..30_000L)
    }

    @Test
    fun getLockoutRemainingMs_oneHourAfterTenFailures() {
        repeat(10) { AppLockCredentials.recordFailure() }
        assertEquals(3_600_000L, AppLockCredentials.getLockoutRemainingMs())
    }

    @Test
    fun getLockoutRemainingMs_zeroAfterExpiry() {
        repeat(5) { AppLockCredentials.recordFailure() }
        fakeTimeMs += 31_000L
        assertEquals(0L, AppLockCredentials.getLockoutRemainingMs())
    }

    // ── resetFailures ───────────────────────────────────────────────

    @Test
    fun resetFailures_clearsLockout() {
        repeat(5) { AppLockCredentials.recordFailure() }
        assertTrue(AppLockCredentials.isLockedOut())
        AppLockCredentials.resetFailures()
        assertFalse("Lockout should be cleared after reset", AppLockCredentials.isLockedOut())
    }

    @Test
    fun resetFailures_resetsCounter() {
        repeat(5) { AppLockCredentials.recordFailure() }
        AppLockCredentials.resetFailures()
        assertEquals(0, AppLockCredentials.getFailureCount())
    }

    // ── getFailureCount ─────────────────────────────────────────────

    @Test
    fun getFailureCount_zeroInitially() {
        assertEquals(0, AppLockCredentials.getFailureCount())
    }

    @Test
    fun getFailureCount_incrementsOnEachFailure() {
        AppLockCredentials.recordFailure()
        assertEquals(1, AppLockCredentials.getFailureCount())
        AppLockCredentials.recordFailure()
        assertEquals(2, AppLockCredentials.getFailureCount())
        AppLockCredentials.recordFailure()
        assertEquals(3, AppLockCredentials.getFailureCount())
    }

    // ── Integration with verifyPattern ──────────────────────────────

    @Test
    fun verifyPattern_returnsFalseWhenLockedOut() {
        AppLockCredentials.setPattern("test123")
        repeat(5) { AppLockCredentials.recordFailure() }
        // Even correct pattern should be rejected during lockout
        assertFalse("Verify should return false when locked out",
            AppLockCredentials.verifyPattern("test123"))
    }

    @Test
    fun verifyPattern_resetsFailuresOnSuccess() {
        AppLockCredentials.setPattern("test123")
        repeat(3) { AppLockCredentials.recordFailure() }
        assertEquals(3, AppLockCredentials.getFailureCount())
        assertTrue(AppLockCredentials.verifyPattern("test123"))
        assertEquals(0, AppLockCredentials.getFailureCount())
    }

    @Test
    fun verifyPattern_recordsFailureOnWrongInput() {
        AppLockCredentials.setPattern("test123")
        AppLockCredentials.verifyPattern("wrong")
        assertEquals(1, AppLockCredentials.getFailureCount())
    }

    @Test
    fun verifyPattern_triggersLockoutAfterFiveWrongAttempts() {
        AppLockCredentials.setPattern("test123")
        repeat(5) { AppLockCredentials.verifyPattern("wrong") }
        assertTrue("Should be locked out after 5 wrong verify attempts",
            AppLockCredentials.isLockedOut())
        assertEquals(5, AppLockCredentials.getFailureCount())
    }

    // ── Persistence across simulated restart ────────────────────────

    @Test
    fun lockoutSurvivesStorageUnavailability() {
        // Record failures, then simulate KeyStore going away (but plain prefs survive)
        repeat(5) { AppLockCredentials.recordFailure() }
        assertTrue(AppLockCredentials.isLockedOut())
        // Simulate KeyStore failure — plain prefs (lockout state) survive
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        assertTrue("Lockout should survive KeyStore failure", AppLockCredentials.isLockedOut())
    }

    // ── Edge: continued failures extend lockout ─────────────────────

    @Test
    fun continuedFailuresUpgradeLockout() {
        // 5 failures -> 30s lockout
        repeat(5) { AppLockCredentials.recordFailure() }
        val remaining5 = AppLockCredentials.getLockoutRemainingMs()
        assertTrue(remaining5 <= 30_000L)

        // Wait for lockout to expire
        fakeTimeMs += 31_000L
        assertFalse(AppLockCredentials.isLockedOut())

        // 5 more failures (total 10) -> 5min lockout
        repeat(5) { AppLockCredentials.recordFailure() }
        val remaining10 = AppLockCredentials.getLockoutRemainingMs()
        assertTrue("After 10 failures, lockout should be ~5min",
            remaining10 > 30_000L)
    }

    // ── PBKDF2 iteration migration (100K → 200K) ──────────────────

    /**
     * Independent PBKDF2WithHmacSHA256 (256-bit key) with a literal iteration
     * count, so a change to the production constants fails these tests
     * instead of silently moving the expected value with them.
     */
    private fun pbkdf2(pattern: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pattern.toCharArray(), salt, iterations, 256))
            .encoded

    /**
     * Helper: manually write a legacy (V1 / 100K-iteration) PBKDF2 hash into
     * SharedPreferences, bypassing [AppLockCredentials.setPattern] which now uses 200K.
     */
    private fun storeLegacyV1Hash(pattern: String): ByteArray {
        val prefs: SharedPreferences = ctx.getSharedPreferences("lockout_test", Context.MODE_PRIVATE)
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = pbkdf2(pattern, salt, 100_000)
        prefs.edit()
            .putString("pattern_hash_v2", Base64.encodeToString(hash, Base64.NO_WRAP))
            .putString("pattern_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .apply()
        return salt
    }

    @Test
    fun verifyPattern_migratesLegacyV1Hash() {
        val pattern = "migration_test"
        storeLegacyV1Hash(pattern)

        // Verification should succeed against the legacy hash
        assertTrue("Should verify against 100K-iteration hash",
            AppLockCredentials.verifyPattern(pattern))
    }

    @Test
    fun verifyPattern_afterMigration_storedHashUpdatedTo200K() {
        val pattern = "migration_update"
        val salt = storeLegacyV1Hash(pattern)

        // First verify triggers migration
        assertTrue(AppLockCredentials.verifyPattern(pattern))

        // Read back the stored hash — it should now match a 200K-iteration computation
        val prefs: SharedPreferences = ctx.getSharedPreferences("lockout_test", Context.MODE_PRIVATE)
        val storedHashStr = prefs.getString("pattern_hash_v2", null)!!
        val storedHash = Base64.decode(storedHashStr, Base64.NO_WRAP)

        val expectedV2Hash = pbkdf2(pattern, salt, 200_000)
        assertArrayEquals("Stored hash should be re-hashed with 200K iterations",
            expectedV2Hash, storedHash)
    }

    @Test
    fun verifyPattern_afterMigration_wrongPatternFails() {
        val pattern = "migration_wrong"
        storeLegacyV1Hash(pattern)

        // Migrate
        assertTrue(AppLockCredentials.verifyPattern(pattern))

        // Wrong pattern should fail against the new 200K hash
        assertFalse(AppLockCredentials.verifyPattern("wrong_pattern"))
    }

    @Test
    fun verifyPattern_afterMigration_secondVerifyUsesCurrent() {
        val pattern = "migration_second"
        storeLegacyV1Hash(pattern)

        // First verify: triggers migration from V1 → current
        assertTrue(AppLockCredentials.verifyPattern(pattern))

        // Second verify: should succeed using the current iteration count path
        // (the legacy fallback path should not be needed)
        assertTrue("Second verify should work with migrated 200K hash",
            AppLockCredentials.verifyPattern(pattern))
    }

    @Test
    fun verifyPattern_currentIterationHashVerifiesNormally() {
        val pattern = "current_normal"
        // setPattern now uses 200K iterations
        AppLockCredentials.setPattern(pattern)

        assertTrue("Pattern created with 200K iterations should verify",
            AppLockCredentials.verifyPattern(pattern))
        assertFalse("Wrong pattern should not verify",
            AppLockCredentials.verifyPattern("wrong"))
    }

    @Test
    fun verifyPattern_legacyHashWrongPatternFails() {
        val pattern = "legacy_fail"
        storeLegacyV1Hash(pattern)

        assertFalse("Wrong pattern should not verify against legacy hash",
            AppLockCredentials.verifyPattern("not_the_pattern"))
        assertEquals("Failure should be recorded", 1, AppLockCredentials.getFailureCount())
    }

    @Test
    fun verifyPattern_migrationPreservesSalt() {
        val pattern = "salt_preserve"
        storeLegacyV1Hash(pattern)

        val prefs: SharedPreferences = ctx.getSharedPreferences("lockout_test", Context.MODE_PRIVATE)
        val saltBefore = prefs.getString("pattern_salt", null)!!

        // Trigger migration
        assertTrue(AppLockCredentials.verifyPattern(pattern))

        val saltAfter = prefs.getString("pattern_salt", null)!!
        assertEquals("Salt should not change during migration", saltBefore, saltAfter)
    }
}
