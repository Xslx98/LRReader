package com.lanraragi.reader.client.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.ui.scene.SecurityViewModel
import com.lanraragi.reader.ui.scene.SecurityViewModel.SecurityUiEvent
import kotlinx.coroutines.launch
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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/**
 * A fingerprint-bound pattern must survive the keystore key being
 * invalidated (a new fingerprint enrolled): the plain PBKDF2 hash is kept
 * next to the encrypted one, so the pattern still verifies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class KeystoreBoundPatternTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        LRRAuthManager.initializeForTesting(ctx.getSharedPreferences("bound_pattern_test", Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() {
        LRRAuthManager.clear()
    }

    /** A software AES-GCM cipher standing in for the BiometricPrompt-unlocked one. */
    private fun encryptCipher(): Cipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    @Test
    fun boundPatternStaysVerifiableWithoutTheKeystore() {
        LRRAuthManager.setPatternWithCipher("0147", encryptCipher())
        assertTrue(LRRAuthManager.isPatternKeystoreBound())
        assertTrue(LRRAuthManager.canVerifyWithoutKeystore())
        assertTrue(LRRAuthManager.verifyPattern("0147"))
        assertFalse(LRRAuthManager.verifyPattern("0148"))
    }

    @Test
    fun invalidatedKeyFallsBackToThePatternAndSwitchesFingerprintOff() = runTest(UnconfinedTestDispatcher()) {
        LRRAuthManager.setPatternWithCipher("0147", encryptCipher())
        SecuritySettings.putEnableFingerprint(true)
        val vm = SecurityViewModel()
        val received = mutableListOf<SecurityUiEvent>()
        backgroundScope.launch { vm.uiEvent.collect { received += it } }

        vm.onBiometricCipherUnavailable("0147")

        assertEquals(listOf<SecurityUiEvent>(SecurityUiEvent.VerifiedAfterFingerprintChange), received)
        assertFalse(LRRAuthManager.isPatternKeystoreBound())
        assertFalse(SecuritySettings.getEnableFingerprint())
        assertTrue(LRRAuthManager.verifyPattern("0147"))
    }
}
