package com.lanraragi.reader.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.settings.SecuritySettings
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
import com.lanraragi.reader.server.LRRAuthManager
import com.lanraragi.reader.server.SecureCredentialStore

/**
 * The app lock must fail closed: when the secure store cannot be opened the
 * pattern is unreadable, but every gate still has to treat the app as
 * locked. Gates read the plain-prefs mirror through
 * [SecuritySettings.isLockEnabled].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class AppLockFailClosedTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        SecureCredentialStore.initializeForTesting(ctx.getSharedPreferences("fail_closed_test", Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() {
        SecureCredentialStore.clear()
    }

    @Test
    fun settingAndClearingThePatternMaintainsTheMirror() {
        AppLockCredentials.setPattern("0123")
        assertEquals(true, AppLockCredentials.lockEnabledHint())
        assertTrue(SecuritySettings.isLockEnabled())

        AppLockCredentials.setPattern(null)
        assertEquals(false, AppLockCredentials.lockEnabledHint())
        assertFalse(SecuritySettings.isLockEnabled())
    }

    @Test
    fun lockStaysOnWhenTheSecureStoreBecomesUnavailable() {
        AppLockCredentials.setPattern("0123")
        SecureCredentialStore.simulateStorageUnavailableForTesting()

        assertFalse("the pattern itself is unreadable", AppLockCredentials.hasPattern())
        assertFalse(SecureCredentialStore.isSecureStorageAvailable())
        assertTrue("gates must still see the app as locked", SecuritySettings.isLockEnabled())
    }

    @Test
    fun serverUrlMaintainsTheConfiguredMirror() {
        assertNull(LRRAuthManager.configuredHint())
        LRRAuthManager.setServerUrl("http://10.0.2.2:3000/")
        assertEquals(true, LRRAuthManager.configuredHint())
        assertTrue(LRRAuthManager.isConfiguredFast())
    }

    @Test
    fun resetClearsTheLockAndTheCredentials() {
        AppLockCredentials.setPattern("0123")
        LRRAuthManager.setServerUrl("http://10.0.2.2:3000")

        AppLockCredentials.resetAppLockAndCredentials(ctx)

        assertEquals(false, AppLockCredentials.lockEnabledHint())
        assertEquals(false, LRRAuthManager.configuredHint())
        assertFalse(SecuritySettings.isLockEnabled())
    }
}
