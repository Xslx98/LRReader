package com.lanraragi.reader.ui.scene

import android.content.Context
import android.content.DialogInterface
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.lanraragi.reader.R
import com.lanraragi.reader.Settings
import com.lanraragi.reader.client.api.InMemoryValueCipher
import com.lanraragi.reader.client.api.KeystoreSecurePrefs
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.ui.SetSecurityActivity
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Audit 2026-10-06d SEC-08: an app lock saved as the pre-PBKDF2 SHA-256
 * `pattern_hash` cannot be checked and is dropped on upgrade, so the app
 * opens unlocked. That stays, but the user is now told once instead of
 * finding the lock silently gone.
 *
 * Robolectric has no AndroidKeyStore; the store is opened with an in-memory
 * cipher. [LRRAuthManager] is a process-wide singleton, so the init gate and
 * the files are restored in both setUp and tearDown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LegacyLockRemovedNoticeTest {

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
        LRRAuthManager.simulateStorageUnavailableForTesting()
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        controller.create().start().resume()
        Settings.initialize(activity.applicationContext)
        wipeFiles()
        ShadowDialog.reset()
    }

    @After
    fun tearDown() {
        scope.cancel()
        LRRAuthManager.clear()
        LRRAuthManager.resetInitGateForTesting()
        wipeFiles()
        controller.close()
    }

    @Test(timeout = 10_000)
    fun sha256LockOnly_opensUnlocked_andTellsTheUserOnce() {
        storeWith("pattern_hash" to "c2hhMjU2")
        launch()

        assertFalse("the old hash cannot be checked: unlocked", SecuritySettings.isLockEnabled())
        assertFalse("dropped from the store", securePrefs().contains("pattern_hash"))

        LegacyLockRemovedNotice.showIfPending(activity)
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(
            activity.getString(R.string.security_legacy_lock_removed_message),
            dialog.findViewById<TextView>(android.R.id.message)!!.text.toString(),
        )
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(
            SetSecurityActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )

        // The next launch does not repeat it.
        ShadowDialog.reset()
        launch()
        LegacyLockRemovedNotice.showIfPending(activity)
        ShadowLooper.idleMainLooper()
        assertNull(ShadowDialog.getLatestDialog())
    }

    /** Audit 06e: MainActivity is recreated on rotation; an unanswered notice must come back. */
    @Test(timeout = 10_000)
    fun recreateBeforeTheAnswer_showsTheNoticeAgain_untilAButtonIsTapped() {
        storeWith("pattern_hash" to "c2hhMjU2")
        launch()

        LegacyLockRemovedNotice.showIfPending(activity)
        ShadowLooper.idleMainLooper()
        val first = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(first.isShowing)

        // Rotation: MainActivity.onCreate2 calls showIfPending again.
        ShadowDialog.reset()
        controller.recreate()
        activity = controller.get()
        ShadowLooper.idleMainLooper()
        assertFalse("closed with the old activity", first.isShowing)
        assertTrue("not answered yet", LRRAuthManager.isLegacyLockRemovedNoticePending())
        LegacyLockRemovedNotice.showIfPending(activity)
        ShadowLooper.idleMainLooper()
        val second = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(second.isShowing)

        second.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(LRRAuthManager.isLegacyLockRemovedNoticePending())
    }

    @Test(timeout = 10_000)
    fun backAnswersTheNotice_aTapOutsideDoesNot() {
        storeWith("pattern_hash" to "c2hhMjU2")
        launch()

        LegacyLockRemovedNotice.showIfPending(activity)
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertFalse(shadowOf(dialog).isCancelableOnTouchOutside)

        dialog.cancel()
        ShadowLooper.idleMainLooper()
        assertFalse(LRRAuthManager.isLegacyLockRemovedNoticePending())
    }

    @Test(timeout = 10_000)
    fun sha256HashBesideACurrentPattern_staysLocked_noNotice() {
        storeWith("pattern_hash" to "c2hhMjU2", "pattern_hash_v2" to "cGJrZGYy", "pattern_salt" to "c2FsdA==")
        launch()

        assertTrue(SecuritySettings.isLockEnabled())
        assertFalse(LRRAuthManager.isLegacyLockRemovedNoticePending())
    }

    @Test(timeout = 10_000)
    fun noSha256Hash_noNotice() {
        storeWith("pattern_hash_v2" to "cGJrZGYy", "pattern_salt" to "c2FsdA==")
        launch()

        assertFalse(LRRAuthManager.isLegacyLockRemovedNoticePending())
    }

    /** Entries as an older version left them in the secure store. */
    private fun storeWith(vararg entries: Pair<String, String>) {
        val store = KeystoreSecurePrefs.open(securePrefs(), cipher)
        store.edit(commit = true) { entries.forEach { (k, v) -> putString(k, v) } }
        // The store has been migrated from the legacy file already.
        plainPrefs().edit(commit = true) { putBoolean("secure_store_v2", true) }
    }

    /** One process start whose store opens. */
    private fun launch() {
        LRRAuthManager.resetInitGateForTesting()
        LRRAuthManager.secureStoreCipher = { cipher }
        LRRAuthManager.scheduleInitialize(activity, scope)
        scheduler.advanceUntilIdle()
        assertTrue(LRRAuthManager.isSecureStorageAvailable())
    }

    private fun plainPrefs() = activity.getSharedPreferences("lrr_auth_plain", Context.MODE_PRIVATE)

    private fun securePrefs() = activity.getSharedPreferences("lrr_auth_secure", Context.MODE_PRIVATE)

    private fun wipeFiles() {
        plainPrefs().edit(commit = true) { clear() }
        securePrefs().edit(commit = true) { clear() }
    }
}
