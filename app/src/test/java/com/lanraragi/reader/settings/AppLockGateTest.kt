package com.lanraragi.reader.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.server.LRRAuthManager
import com.lanraragi.reader.server.SecureCredentialStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lock is process-level: a fresh process is locked whenever a pattern
 * is set, so a restored scene stack or a directly launched Activity is
 * gated without depending on the cold-start launch scene.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class AppLockGateTest {

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        SecureCredentialStore.initializeForTesting(ctx.getSharedPreferences("gate_test", Context.MODE_PRIVATE))
        AppLockGate.resetForTesting()
    }

    @After
    fun tearDown() {
        SecureCredentialStore.clear()
        AppLockGate.resetForTesting()
    }

    @Test
    fun freshProcessWithAPatternIsLockedUntilUnlocked() {
        LRRAuthManager.setPattern("0123")
        assertTrue(AppLockGate.isLocked())
        AppLockGate.markUnlocked()
        assertFalse(AppLockGate.isLocked())
    }

    @Test
    fun goingToTheBackgroundLocksAgain() {
        LRRAuthManager.setPattern("0123")
        AppLockGate.markUnlocked()
        AppLockGate.onAppBackgrounded()
        assertTrue(AppLockGate.isLocked())
    }

    @Test
    fun withoutAPatternNothingIsLocked_andSettingOneDoesNotLockUntilBackground() {
        assertFalse(AppLockGate.isLocked())
        LRRAuthManager.setPattern("0123")
        assertFalse("the user who just set it stays in", AppLockGate.isLocked())
        AppLockGate.onAppBackgrounded()
        assertTrue(AppLockGate.isLocked())
    }

    @Test
    fun lockHoldsWhenTheSecureStoreIsUnavailable() {
        LRRAuthManager.setPattern("0123")
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        assertTrue(AppLockGate.isLocked())
    }
}
