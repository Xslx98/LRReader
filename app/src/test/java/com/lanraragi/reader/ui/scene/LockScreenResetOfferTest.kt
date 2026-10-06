package com.lanraragi.reader.ui.scene

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.Settings
import com.lanraragi.reader.client.api.InMemoryValueCipher
import com.lanraragi.reader.client.api.LRRAuthManager
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06d SEC-01: the lock screen offered "Reset app lock" (which
 * removes the pattern and every saved API key) on the FIRST failed store
 * open, while the reset outside the lock screen waits for two failed
 * launches in a row. A KeyStore error that heals by itself then cost every
 * key after one tap. Now the lock screen waits too, and only launches that
 * show the failure count: widget, worker and other background starts do not
 * (audit 06c N-new-1 note). A user who reached the lock screen only because
 * the lock state was unreadable is not told to reset an app lock (06d R3).
 *
 * Robolectric has no AndroidKeyStore: the production init fails unless
 * [LRRAuthManager.secureStoreCipher] is swapped. [LRRAuthManager] is a
 * process-wide singleton; [LRRAuthManager.resetInitGateForTesting] stands
 * for a new process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LockScreenResetOfferTest {

    private lateinit var ctx: Context
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(
        StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> println("contained: $t") }
    )

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        LRRAuthManager.resetInitGateForTesting()
        LRRAuthManager.simulateStorageUnavailableForTesting()
        wipeFiles()
    }

    @After
    fun tearDown() {
        scope.cancel()
        LRRAuthManager.clear()
        LRRAuthManager.resetInitGateForTesting()
        wipeFiles()
    }

    @Test(timeout = 10_000)
    fun lockSet_firstFailedLaunch_offersOnlyTryAgain() {
        setLock()

        val prompt = shownFailedLaunch()

        assertEquals(R.string.security_storage_unavailable_first_message, prompt.message)
        assertNull("no reset on the first failure", prompt.resetButton)
        assertEquals(1, LRRAuthManager.consecutiveStoreOpenFailures())
    }

    @Test(timeout = 10_000)
    fun lockSet_secondFailedLaunchInARow_offersTheReset() {
        setLock()
        shownFailedLaunch()

        val prompt = shownFailedLaunch()

        assertEquals(R.string.security_storage_unavailable_message, prompt.message)
        assertEquals(R.string.security_reset_app_lock, prompt.resetButton)
        assertEquals(R.string.security_reset_app_lock_confirm, prompt.resetConfirm)
    }

    @Test(timeout = 10_000)
    fun backgroundStarts_doNotCount() {
        setLock()
        repeat(3) { failedLaunch() } // widget update, download worker, ...

        val prompt = shownFailedLaunch()

        assertEquals(1, LRRAuthManager.consecutiveStoreOpenFailures())
        assertNull(prompt.resetButton)
    }

    @Test(timeout = 10_000)
    fun oneLaunch_promptShownAgain_countsOnce() {
        setLock()
        shownFailedLaunch()

        // Rotation, or "cancel" on the reset confirmation, shows the prompt again.
        val again = SecurityViewModel().unavailablePrompt()

        assertEquals(1, LRRAuthManager.consecutiveStoreOpenFailures())
        assertNull(again.resetButton)
    }

    @Test(timeout = 10_000)
    fun aLaunchThatOpensTheStore_restartsTheCount() {
        setLock()
        shownFailedLaunch()
        openedLaunch()
        assertEquals(0, LRRAuthManager.consecutiveStoreOpenFailures())

        assertNull(shownFailedLaunch().resetButton)
    }

    /** 06d R3: a lock-less <= v1.26 upgrader whose migration failed must not read "Reset app lock". */
    @Test(timeout = 10_000)
    fun lockStateUnknown_saysTheStateCannotBeChecked() {
        writeLegacyStoreFile()

        val first = shownFailedLaunch()
        assertEquals(R.string.security_lock_state_unknown_first_message, first.message)
        assertNull(first.resetButton)

        val second = shownFailedLaunch()
        assertEquals(R.string.security_lock_state_unknown_message, second.message)
        assertEquals(R.string.lrr_reset_credentials, second.resetButton)
        assertEquals(R.string.lrr_reset_credentials_confirm, second.resetConfirm)
    }

    /** 06c note: no test that the reset clears the counter. */
    @Test(timeout = 10_000)
    fun theReset_clearsTheCount() {
        setLock()
        shownFailedLaunch()
        shownFailedLaunch()
        assertEquals(2, LRRAuthManager.consecutiveStoreOpenFailures())

        LRRAuthManager.resetAppLockAndCredentials(ctx)

        assertEquals(0, LRRAuthManager.consecutiveStoreOpenFailures())
        assertTrue(!plainPrefs().contains("secure_store_open_failures"))
    }

    /** A process start whose store fails to open and that shows no screen. */
    private fun failedLaunch() {
        LRRAuthManager.resetInitGateForTesting()
        LRRAuthManager.scheduleInitialize(ctx, scope)
        scheduler.advanceUntilIdle()
    }

    /** A failed launch that reaches the lock screen's prompt. */
    private fun shownFailedLaunch(): StorageUnavailablePrompt.Text {
        failedLaunch()
        assertEquals(SecurityViewModel.StorageState.UNAVAILABLE, SecurityViewModel().storageState())
        return SecurityViewModel().unavailablePrompt()
    }

    private fun openedLaunch() {
        LRRAuthManager.resetInitGateForTesting()
        LRRAuthManager.secureStoreCipher = { InMemoryValueCipher() }
        // A fresh cipher cannot read the old store; start from an empty one.
        securePrefs().edit(commit = true) { clear() }
        LRRAuthManager.scheduleInitialize(ctx, scope)
        scheduler.advanceUntilIdle()
        assertTrue(LRRAuthManager.isSecureStorageAvailable())
    }

    private fun setLock() {
        plainPrefs().edit(commit = true) { putBoolean("app_lock_enabled", true) }
    }

    private fun plainPrefs() = ctx.getSharedPreferences("lrr_auth_plain", Context.MODE_PRIVATE)

    private fun securePrefs() = ctx.getSharedPreferences("lrr_auth_secure", Context.MODE_PRIVATE)

    private fun writeLegacyStoreFile() {
        val file = File(ctx.applicationInfo.dataDir, "shared_prefs/lrr_auth_encrypted.xml")
        file.parentFile?.mkdirs()
        file.writeText("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n")
    }

    private fun wipeFiles() {
        plainPrefs().edit(commit = true) { clear() }
        securePrefs().edit(commit = true) { clear() }
        File(ctx.applicationInfo.dataDir, "shared_prefs/lrr_auth_encrypted.xml").delete()
    }
}
