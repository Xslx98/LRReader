package com.lanraragi.reader.ui.scene

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.Settings
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.ui.scene.SecurityViewModel.FingerprintStart
import com.lanraragi.reader.ui.scene.SecurityViewModel.SecurityUiEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Key
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint unlock must be proven by the keystore-bound pattern key, not
 * by the prompt's success callback alone (audit 2026-10-06e SEC-01): a
 * fingerprint enrolled after the lock was set invalidates that key.
 * A software AES key stands in for the AndroidKeyStore one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FingerprintUnlockTest {

    private lateinit var ctx: Context
    private lateinit var originalLoader: () -> Key?
    private val patternKey: SecretKey = newKey()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        LRRAuthManager.initializeForTesting(ctx.getSharedPreferences("fingerprint_unlock_test", Context.MODE_PRIVATE))
        originalLoader = LRRAuthManager.patternKeyLoader
        LRRAuthManager.patternKeyLoader = { patternKey }
    }

    @After
    fun tearDown() {
        LRRAuthManager.patternKeyLoader = originalLoader
        SecuritySettings.putEnableFingerprint(false)
        LRRAuthManager.clear()
    }

    /** Sets pattern "0147" bound to [patternKey], fingerprint unlock on. */
    private fun bindPattern() {
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, patternKey) }
        LRRAuthManager.setPatternWithCipher("0147", encrypt)
        SecuritySettings.putEnableFingerprint(true)
    }

    private fun TestScope.events(vm: SecurityViewModel): List<SecurityUiEvent> {
        val received = mutableListOf<SecurityUiEvent>()
        backgroundScope.launch { vm.uiEvent.collect { received += it } }
        return received
    }

    @Test
    fun boundKeyCipherUnlocks() = runTest(UnconfinedTestDispatcher()) {
        bindPattern()
        val vm = SecurityViewModel()
        val received = events(vm)

        val start = vm.startFingerprintUnlock()
        assertTrue(start is FingerprintStart.Ready)
        vm.onFingerprintAuthenticated((start as FingerprintStart.Ready).cipher)

        assertEquals(listOf<SecurityUiEvent>(SecurityUiEvent.FingerprintVerified), received)
    }

    @Test
    fun promptSuccessWithoutAWorkingCipherDoesNotUnlock() = runTest(UnconfinedTestDispatcher()) {
        bindPattern()
        val vm = SecurityViewModel()
        val received = events(vm)

        // No CryptoObject at all (the old bare prompt).
        vm.onFingerprintAuthenticated(null)
        // A cipher over another key (GCM tag check fails).
        val foreign = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, newKey(), GCMParameterSpec(128, ByteArray(12)))
        }
        vm.onFingerprintAuthenticated(foreign)
        // A cipher the keystore refuses to use (never initialised).
        vm.onFingerprintAuthenticated(Cipher.getInstance("AES/GCM/NoPadding"))

        assertEquals(List(3) { SecurityUiEvent.FingerprintUnverified }, received)
        assertTrue(SecuritySettings.isLockEnabled())
    }

    @Test
    fun invalidatedKeyTurnsFingerprintOffAndKeepsThePattern() = runTest(UnconfinedTestDispatcher()) {
        bindPattern()
        LRRAuthManager.patternKeyLoader = { throw KeyPermanentlyInvalidatedException() }
        val vm = SecurityViewModel()
        val received = events(vm)

        assertEquals(FingerprintStart.TurnedOff(R.string.security_fingerprint_changed), vm.startFingerprintUnlock())
        assertFalse(SecuritySettings.getEnableFingerprint())
        assertEquals(FingerprintStart.Off, vm.startFingerprintUnlock())
        assertTrue(SecuritySettings.isLockEnabled())

        // The pattern still unlocks; the user was already told, so no second notice.
        vm.onBiometricCipherUnavailable("0147")
        assertEquals(listOf<SecurityUiEvent>(SecurityUiEvent.PatternVerified), received)
    }

    @Test
    fun unboundPatternTurnsFingerprintOff() {
        LRRAuthManager.setPattern("0147")
        SecuritySettings.putEnableFingerprint(true)

        assertEquals(
            FingerprintStart.TurnedOff(R.string.security_fingerprint_not_bound),
            SecurityViewModel().startFingerprintUnlock(),
        )
        assertFalse(SecuritySettings.getEnableFingerprint())
        assertTrue(LRRAuthManager.verifyPattern("0147"))
    }

    @Test
    fun missingKeyTurnsFingerprintOff() {
        bindPattern()
        LRRAuthManager.patternKeyLoader = { null }

        assertEquals(
            FingerprintStart.TurnedOff(R.string.security_fingerprint_not_bound),
            SecurityViewModel().startFingerprintUnlock(),
        )
        assertFalse(SecuritySettings.getEnableFingerprint())
    }

    @Test
    fun transientKeystoreFailureKeepsTheSetting() {
        bindPattern()
        LRRAuthManager.patternKeyLoader = { throw KeyStoreException("busy") }

        assertEquals(FingerprintStart.Unavailable, SecurityViewModel().startFingerprintUnlock())
        assertTrue(SecuritySettings.getEnableFingerprint())
    }
}
