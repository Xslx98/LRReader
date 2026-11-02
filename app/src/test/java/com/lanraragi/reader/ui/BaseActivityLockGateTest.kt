package com.lanraragi.reader.ui

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.Settings
import com.lanraragi.reader.server.LRRAuthManager
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.settings.AppLockGate
import com.lanraragi.reader.settings.SecuritySettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app-wide Activity policies ([ActivityPolicies], applied by [BaseActivity] and,
 * through StageActivity's hooks, by MainActivity): an Activity created or resumed while
 * the app is locked hands off to the lock-screen host and finishes, and the lock-only
 * FLAG_SECURE on API < 33 covers the window whenever it may be snapshotted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class BaseActivityLockGateTest {

    class TestActivity : BaseActivity() {
        override fun getThemeResId(theme: Int): Int = R.style.AppTheme_Main

        override fun lockResumeIntent(): Intent = RESUME
    }

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        SecureCredentialStore.initializeForTesting(ctx.getSharedPreferences("base_activity_test", Context.MODE_PRIVATE))
        AppLockGate.resetForTesting()
        SecuritySettings.putEnabledSecurity(false)
    }

    @After
    fun tearDown() {
        SecureCredentialStore.clear()
        AppLockGate.resetForTesting()
        SecuritySettings.putEnabledSecurity(false)
    }

    private fun TestActivity.isSecure(): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun TestActivity.assertRedirectedToLockScreen() {
        assertTrue(isFinishing)
        val started = shadowOf(this).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertSame(RESUME, AppLockGate.consumeResumeIntent())
    }

    @Test
    fun createdWhileLocked_handsOffToTheLockScreenWithItsResumeIntent() {
        LRRAuthManager.setPattern("0123")

        val activity = Robolectric.buildActivity(TestActivity::class.java).create().get()

        activity.assertRedirectedToLockScreen()
    }

    @Test
    fun unlocked_staysAndStartsNothing() {
        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()

        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isSecure())
    }

    @Test
    fun resumedAfterTheAppWentToTheBackground_handsOffToTheLockScreen() {
        LRRAuthManager.setPattern("0123")
        AppLockGate.markUnlocked()
        val controller = Robolectric.buildActivity(TestActivity::class.java).setup()
        assertFalse(controller.get().isFinishing)

        controller.pause().stop()
        AppLockGate.onAppBackgrounded()
        controller.restart().resume()

        controller.get().assertRedirectedToLockScreen()
    }

    @Test
    fun userSecureMode_keepsTheWindowSecure() {
        SecuritySettings.putEnabledSecurity(true)

        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()

        assertTrue(activity.isSecure())
    }

    @Test
    fun appLockBelowApi33_securesThePausedWindowOnly() {
        LRRAuthManager.setPattern("0123")
        AppLockGate.markUnlocked()
        val controller = Robolectric.buildActivity(TestActivity::class.java).setup()
        assertFalse("resumed and unlocked: screenshots allowed", controller.get().isSecure())

        controller.pause()
        assertTrue("paused: hidden from the recents snapshot", controller.get().isSecure())

        controller.resume()
        assertFalse(controller.get().isSecure())
    }

    @Test
    fun onAppUnlocked_liftsTheLockOnlyFlag() {
        LRRAuthManager.setPattern("0123")
        AppLockGate.markUnlocked()
        val controller = Robolectric.buildActivity(TestActivity::class.java).setup()
        controller.pause()
        assertTrue(controller.get().isSecure())

        (controller.get() as AppUnlockHost).onAppUnlocked()

        assertFalse(controller.get().isSecure())
    }

    private companion object {
        val RESUME = Intent("test.resume")
    }
}
