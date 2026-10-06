package com.lanraragi.reader.client.api

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.settings.AppLockGate
import com.lanraragi.reader.settings.SecuritySettings
import java.io.File
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06c SEC-01: the plain lock mirror (`app_lock_enabled`) only
 * exists since v1.27.0. A user upgrading from an older version with a
 * pattern must not get an unlocked session because the secure store opens
 * slowly or not at all: without the mirror the lock is decided from what is
 * readable without the keystore, and "locked" when that is not possible.
 *
 * Robolectric has no AndroidKeyStore, so the production init always takes
 * its failure branch unless [LRRAuthManager.secureStoreCipher] is swapped.
 * [LRRAuthManager] and [AppLockGate] are process-wide singletons: the init
 * gate and the files are restored in both setUp and tearDown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class AppLockUpgradeFailClosedTest {

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
        // Production has no store until init opens one; earlier tests leave one behind.
        LRRAuthManager.simulateStorageUnavailableForTesting()
        wipeFiles()
        AppLockGate.resetForTesting()
    }

    @After
    fun tearDown() {
        scope.cancel()
        LRRAuthManager.clear()
        LRRAuthManager.resetInitGateForTesting()
        wipeFiles()
        AppLockGate.resetForTesting()
    }

    @Test(timeout = 10_000)
    fun noMirror_storeFailed_patternSlotPresent_isLocked() {
        // KeystoreSecurePrefs keeps slot names in plaintext; the value is unreadable here.
        securePrefs().edit(commit = true) { putString("pattern_hash_v2", "ciphertext") }
        failedLaunch()

        assertFalse(LRRAuthManager.isSecureStorageAvailable())
        assertNull("mirror absent: written by <= v1.26 or never", LRRAuthManager.lockEnabledHint())
        assertTrue(SecuritySettings.isLockEnabled())
        assertTrue(AppLockGate.isLocked())
        assertEquals("the answer is remembered", true, LRRAuthManager.lockEnabledHint())
    }

    @Test(timeout = 10_000)
    fun noMirror_storeFailed_keystoreBoundFlagPresent_isLocked() {
        // Plain flag every version since v1.12.4 writes with the pattern.
        plainPrefs().edit(commit = true) { putBoolean("pattern_keystore_bound", false) }
        failedLaunch()

        assertTrue(SecuritySettings.isLockEnabled())
        assertTrue(AppLockGate.isLocked())
    }

    @Test(timeout = 10_000)
    fun noMirror_storeFailed_noPatternAnywhere_isNotLocked() {
        securePrefs().edit(commit = true) { putString("api_key_1", "ciphertext") }
        failedLaunch()

        assertFalse(SecuritySettings.isLockEnabled())
        assertFalse(AppLockGate.isLocked())
        assertEquals("the answer is remembered", false, LRRAuthManager.lockEnabledHint())
    }

    @Test(timeout = 10_000)
    fun noMirror_legacyStoreNotMigrated_storeFailed_failsClosed() {
        writeLegacyStoreFile()
        failedLaunch()
        failedLaunch()

        assertTrue("cannot tell without the keystore: locked", SecuritySettings.isLockEnabled())
        assertTrue(AppLockGate.isLocked())
        assertNull("an unknown answer is not remembered", LRRAuthManager.lockEnabledHint())
        // The lock screen owns the reset in this state.
        assertFalse(LRRAuthManager.canOfferCredentialReset())
    }

    @Test(timeout = 10_000)
    fun noMirror_initPendingOnMain_isNotCachedAsUnlocked() {
        writeLegacyStoreFile()
        LRRAuthManager.scheduleInitialize(ctx, scope) // not advanced: init still running
        LRRAuthManager.mainThreadInitTimeoutMs = 100
        assertTrue(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())

        assertTrue("store still opening: locked", AppLockGate.isLocked())
        assertTrue(AppLockGate.isLocked())

        // The migration finishes (legacy store copied and removed) and the store opens without a pattern.
        File(ctx.applicationInfo.dataDir, "shared_prefs/lrr_auth_encrypted.xml").delete()
        LRRAuthManager.secureStoreCipher = { InMemoryValueCipher() }
        scheduler.advanceUntilIdle()

        assertTrue(LRRAuthManager.isSecureStorageAvailable())
        assertEquals(false, LRRAuthManager.lockEnabledHint())
        assertFalse("decided now, not cached from the pending state", AppLockGate.isLocked())
    }

    @Test(timeout = 10_000)
    fun noMirror_storeOpens_mirrorWrittenFromTheStore() {
        LRRAuthManager.secureStoreCipher = { InMemoryValueCipher() }
        LRRAuthManager.scheduleInitialize(ctx, scope)
        scheduler.advanceUntilIdle()

        assertEquals(false, LRRAuthManager.lockEnabledHint())
        assertFalse(SecuritySettings.isLockEnabled())
    }

    private fun failedLaunch() {
        LRRAuthManager.resetInitGateForTesting()
        LRRAuthManager.scheduleInitialize(ctx, scope)
        scheduler.advanceUntilIdle()
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
