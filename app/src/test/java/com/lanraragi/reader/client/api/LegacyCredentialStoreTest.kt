package com.lanraragi.reader.client.api

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * Ruling R26: androidx.security is gone, so the EncryptedSharedPreferences
 * store of an install updated straight from v1.x cannot be read. Init must
 * say so — reauth prompt for the API keys, lock-removed notice for a pattern
 * — and drop the store, instead of opening silently unconfigured and unlocked.
 *
 * [LRRAuthManager] is a process-wide singleton: init gate and files are
 * restored in both setUp and tearDown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LegacyCredentialStoreTest {

    private lateinit var ctx: Context
    private val scheduler = TestCoroutineScheduler()
    // One key for every launch, as the AndroidKeyStore key survives restarts.
    private val cipher = InMemoryValueCipher()
    private val scope = CoroutineScope(
        StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> println("contained: $t") }
    )

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        LRRAuthManager.resetInitGateForTesting()
        LRRAuthManager.simulateStorageUnavailableForTesting()
        LRRAuthManager.clear()
        LRRAuthManager.resetInitGateForTesting()
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
    fun updateFromV1_configuredWithLock_asksForKeysAndReportsTheLostLock() {
        writeLegacyStoreFile()
        plainPrefs().edit(commit = true) {
            putBoolean("was_configured", true)
            putBoolean("pattern_keystore_bound", true)
        }

        launch()

        assertTrue(LRRAuthManager.isSecureStorageAvailable())
        assertTrue("the old API keys are gone: ask for them", LRRAuthManager.isNeedsReauthentication())
        assertTrue("the old lock is gone: tell the user", LRRAuthManager.isLegacyLockRemovedNoticePending())
        assertEquals("the app opens unlocked", false, LRRAuthManager.lockEnabledHint())
        assertFalse(plainPrefs().contains("pattern_keystore_bound"))
        assertFalse("the unreadable store is dropped", legacyFile().exists())
    }

    @Test(timeout = 10_000)
    fun updateFromV1_neverConfigured_noLock_opensQuietly() {
        writeLegacyStoreFile()

        launch()

        assertFalse(LRRAuthManager.isNeedsReauthentication())
        assertFalse(LRRAuthManager.isLegacyLockRemovedNoticePending())
        assertFalse(legacyFile().exists())
    }

    @Test(timeout = 10_000)
    fun alreadyHandled_laterLaunchesDoNotAskAgain() {
        writeLegacyStoreFile()
        plainPrefs().edit(commit = true) { putBoolean("was_configured", true) }
        launch()
        assertTrue(LRRAuthManager.isNeedsReauthentication())

        LRRAuthManager.clear()
        launch()

        assertFalse(LRRAuthManager.isNeedsReauthentication())
    }

    private fun launch() {
        LRRAuthManager.resetInitGateForTesting()
        LRRAuthManager.secureStoreCipher = { cipher }
        LRRAuthManager.scheduleInitialize(ctx, scope)
        scheduler.advanceUntilIdle()
    }

    private fun plainPrefs() = ctx.getSharedPreferences("lrr_auth_plain", Context.MODE_PRIVATE)

    private fun legacyFile() = File(ctx.applicationInfo.dataDir, "shared_prefs/lrr_auth_encrypted.xml")

    private fun writeLegacyStoreFile() {
        val file = legacyFile()
        file.parentFile?.mkdirs()
        file.writeText("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n")
    }

    private fun wipeFiles() {
        plainPrefs().edit(commit = true) { clear() }
        ctx.getSharedPreferences("lrr_auth_secure", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        legacyFile().delete()
    }
}
