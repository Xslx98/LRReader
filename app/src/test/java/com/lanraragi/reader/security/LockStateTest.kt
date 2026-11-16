package com.lanraragi.reader.security

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.settings.SecuritySettings
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
 * [AppLockCredentials.lockState], the one answer every lock gate reads, and
 * the boot migration of the oldest plaintext pattern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LockStateTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        SecureCredentialStore.initializeForTesting(ctx.getSharedPreferences("lock_state_test", Context.MODE_PRIVATE))
        Settings.putString("security", "")
    }

    @After
    fun tearDown() {
        Settings.putString("security", "")
        SecureCredentialStore.clear()
    }

    @Test
    fun noPattern_isNone() {
        AppLockCredentials.setPattern(null)
        assertEquals(LockState.None, AppLockCredentials.lockState())
        assertFalse(SecuritySettings.isLockEnabled())
    }

    @Test
    fun aPattern_isPattern_withItsKeystoreBinding() {
        AppLockCredentials.setPattern("0123")
        assertEquals(LockState.Pattern(keystoreBound = false), AppLockCredentials.lockState())

        SecureCredentialStore.plainPrefs!!.edit { putBoolean("pattern_keystore_bound", true) }
        assertEquals(LockState.Pattern(keystoreBound = true), AppLockCredentials.lockState())
    }

    @Test
    fun theStoreGoingAway_keepsThePatternState() {
        AppLockCredentials.setPattern("0123")
        SecureCredentialStore.simulateStorageUnavailableForTesting()

        assertTrue("fail closed", AppLockCredentials.lockState().isLocked)
    }

    @Test
    fun unknown_isLocked() {
        assertTrue(LockState.Unknown.isLocked)
        assertFalse(LockState.None.isLocked)
    }

    @Test
    fun legacyPlaintextPattern_isLocked_untilTheBootStepHashesIt() {
        AppLockCredentials.setPattern(null)
        Settings.putString("security", "0369")
        assertTrue("an unmigrated plaintext pattern locks", SecuritySettings.isLockEnabled())
        assertTrue(SecuritySettings.hasPattern())

        SecuritySettings.migrateLegacyPattern()

        assertEquals("", Settings.getString("security", ""))
        assertTrue(AppLockCredentials.hasPattern())
        assertTrue(AppLockCredentials.verifyPattern("0369"))
        assertTrue(SecuritySettings.isLockEnabled())
    }

    @Test
    fun legacyPlaintextPattern_storeUnavailable_staysAndStaysLocked() {
        Settings.putString("security", "0369")
        SecureCredentialStore.simulateStorageUnavailableForTesting()

        SecuritySettings.migrateLegacyPattern()

        assertEquals("kept for the next boot", "0369", Settings.getString("security", ""))
        assertTrue(SecuritySettings.isLockEnabled())
    }

    @Test
    fun readingTheLockNeverMigrates() {
        AppLockCredentials.setPattern(null)
        Settings.putString("security", "0369")

        SecuritySettings.isLockEnabled()
        SecuritySettings.hasPattern()

        assertEquals("getters have no side effect", "0369", Settings.getString("security", ""))
    }
}
