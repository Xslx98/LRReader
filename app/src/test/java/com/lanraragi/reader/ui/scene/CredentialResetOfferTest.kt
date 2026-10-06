package com.lanraragi.reader.ui.scene

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.InMemoryValueCipher
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Audit 2026-10-06b REL-01: a secure store that can never be read again
 * (copied from another phone, keystore key lost) used to be resettable only
 * from the lock screen, which a user without an app lock never sees. The
 * "credentials unavailable" dialogs those users land on must offer the reset
 * when the store's init finished and failed on two launches in a row, and
 * only then: not after a single failure (audit 06c N-new-1), not while init
 * is still running (transient, audit SEC-04), not on a healthy store, and not
 * when a lock is set (the lock screen owns the reset there).
 *
 * [LRRAuthManager] is a process-wide singleton, so the init gate is restored
 * in both setUp and tearDown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CredentialResetOfferTest {

    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity
    private val scheduler = TestCoroutineScheduler()
    private val cipher = InMemoryValueCipher()
    private val scope = CoroutineScope(
        StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> println("contained: $t") }
    )

    @Before
    fun setUp() {
        LRRAuthManager.resetInitGateForTesting()
        // Production has no store until init opens one; earlier tests leave one behind.
        LRRAuthManager.simulateStorageUnavailableForTesting()
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        controller.create().start().resume()
        plainPrefs().edit(commit = true) { clear() }
        activity.getSharedPreferences("lrr_auth_secure", Context.MODE_PRIVATE).edit(commit = true) { clear() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        LRRAuthManager.clear()
        LRRAuthManager.resetInitGateForTesting()
        controller.close()
    }

    @Test(timeout = 10_000)
    fun storeThatFailedToOpenTwiceInARow_noLock_offersTheReset() {
        failedLaunch()
        failedLaunch()

        assertTrue(LRRAuthManager.canOfferCredentialReset())
        val reset = showErrorDialog().getButton(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(View.VISIBLE, reset.visibility)
        assertEquals(activity.getString(R.string.lrr_reset_credentials), reset.text.toString())

        // The button asks first; nothing is wiped by the first tap.
        reset.performClick()
        ShadowLooper.idleMainLooper()
        val confirm = ShadowDialog.getLatestDialog() as AlertDialog
        val message = confirm.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertEquals(activity.getString(R.string.lrr_reset_credentials_confirm), message)
    }

    /**
     * Audit 2026-10-06c N-new-1: one failed open may be a KeyStore error
     * that heals by the next launch; the reset would cost every API key.
     */
    @Test(timeout = 10_000)
    fun storeThatFailedToOpenOnce_offersNoReset() {
        failedLaunch()

        assertFalse(LRRAuthManager.isSecureStorageAvailable())
        assertEquals(1, LRRAuthManager.consecutiveStoreOpenFailures())
        assertFalse(LRRAuthManager.canOfferCredentialReset())
        assertResetHidden(showErrorDialog())
    }

    @Test(timeout = 10_000)
    fun aLaunchThatOpensTheStore_restartsTheCount() {
        failedLaunch()
        openedLaunch()
        assertEquals(0, LRRAuthManager.consecutiveStoreOpenFailures())

        failedLaunch()
        assertFalse("not two failures in a row", LRRAuthManager.canOfferCredentialReset())
        failedLaunch()
        assertTrue(LRRAuthManager.canOfferCredentialReset())
    }

    @Test(timeout = 10_000)
    fun storeStillStarting_offersNoReset() {
        LRRAuthManager.scheduleInitialize(activity, scope) // never advanced: init still running
        LRRAuthManager.mainThreadInitTimeoutMs = 100

        assertFalse(LRRAuthManager.canOfferCredentialReset())
        assertResetHidden(showErrorDialog())
    }

    @Test(timeout = 10_000)
    fun healthyStore_offersNoReset() {
        LRRAuthManager.initializeForTesting(activity.getSharedPreferences("reset_offer_test", Context.MODE_PRIVATE))

        assertFalse(LRRAuthManager.canOfferCredentialReset())
        assertResetHidden(showErrorDialog())
    }

    @Test(timeout = 10_000)
    fun storeThatFailedToOpen_withLock_leavesTheResetToTheLockScreen() {
        plainPrefs().edit(commit = true) { putBoolean("app_lock_enabled", true) }
        failedLaunch()
        failedLaunch()

        assertFalse(LRRAuthManager.isSecureStorageAvailable())
        assertFalse(LRRAuthManager.canOfferCredentialReset())
        assertResetHidden(showErrorDialog())
    }

    /** One process start whose store fails to open (Robolectric has no AndroidKeyStore). */
    private fun failedLaunch() {
        LRRAuthManager.resetInitGateForTesting()
        LRRAuthManager.scheduleInitialize(activity, scope)
        scheduler.advanceUntilIdle()
    }

    /** One process start whose store opens. */
    private fun openedLaunch() {
        LRRAuthManager.resetInitGateForTesting()
        LRRAuthManager.secureStoreCipher = { cipher }
        LRRAuthManager.scheduleInitialize(activity, scope)
        scheduler.advanceUntilIdle()
        assertTrue(LRRAuthManager.isSecureStorageAvailable())
    }

    private fun plainPrefs() = activity.getSharedPreferences("lrr_auth_plain", Context.MODE_PRIVATE)

    private fun showErrorDialog(): AlertDialog {
        CredentialResetDialog.showSecureStorageError(activity)
        ShadowLooper.idleMainLooper()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun assertResetHidden(dialog: AlertDialog) {
        assertNotEquals(View.VISIBLE, dialog.getButton(DialogInterface.BUTTON_NEGATIVE).visibility)
    }
}
