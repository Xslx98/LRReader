package com.lanraragi.reader.client.api

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
        LRRAuthManager.initializeForTesting(ctx.getSharedPreferences("fail_closed_test", Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() {
        LRRAuthManager.clear()
    }

    @Test
    fun settingAndClearingThePatternMaintainsTheMirror() {
        LRRAuthManager.setPattern("0123")
        assertEquals(true, LRRAuthManager.lockEnabledHint())
        assertTrue(SecuritySettings.isLockEnabled())

        LRRAuthManager.setPattern(null)
        assertEquals(false, LRRAuthManager.lockEnabledHint())
        assertFalse(SecuritySettings.isLockEnabled())
    }

    @Test
    fun lockStaysOnWhenTheSecureStoreBecomesUnavailable() {
        LRRAuthManager.setPattern("0123")
        LRRAuthManager.simulateStorageUnavailableForTesting()

        assertFalse("the pattern itself is unreadable", LRRAuthManager.hasPattern())
        assertFalse(LRRAuthManager.isSecureStorageAvailable())
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
        LRRAuthManager.setPattern("0123")
        LRRAuthManager.setServerUrl("http://10.0.2.2:3000")

        LRRAuthManager.resetAppLockAndCredentials(ctx)

        assertEquals(false, LRRAuthManager.lockEnabledHint())
        assertEquals(false, LRRAuthManager.configuredHint())
        assertFalse(SecuritySettings.isLockEnabled())
    }
}
