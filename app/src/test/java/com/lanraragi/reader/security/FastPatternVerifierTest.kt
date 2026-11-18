package com.lanraragi.reader.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.ui.scene.SecurityViewModel
import com.lanraragi.reader.ui.scene.SecurityViewModel.SecurityUiEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The fast pattern verifier: the keystore HMAC (a software key here, as
 * Robolectric has no AndroidKeyStore) of a 10,000-round PBKDF2, which
 * replaces the 200,000-round hash on the next unlock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FastPatternVerifierTest {

    private lateinit var prefs: SharedPreferences
    private var macKey: SecretKey? = newMacKey()
    private val defaultLoader = PatternMacKey.loader
    private val defaultRemover = PatternMacKey.remover

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Settings.initialize(ctx)
        prefs = ctx.getSharedPreferences("fast_pattern_test", Context.MODE_PRIVATE)
        SecureCredentialStore.initializeForTesting(prefs)
        PatternMacKey.loader = { create ->
            if (macKey == null && create) macKey = newMacKey()
            macKey
        }
        PatternMacKey.remover = { macKey = null }
        PatternMacKey.forgetCachedKey()
    }

    @After
    fun tearDown() {
        SecureCredentialStore.clear()
        PatternMacKey.loader = defaultLoader
        PatternMacKey.remover = defaultRemover
        PatternMacKey.forgetCachedKey()
    }

    private fun newMacKey(): SecretKey = KeyGenerator.getInstance("HmacSHA256").generateKey()

    /** Independent PBKDF2 with a literal iteration count, so a changed constant fails here. */
    private fun pbkdf2(pattern: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pattern.toCharArray(), salt, iterations, 256))
            .encoded

    private fun hmac(data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(macKey)
            doFinal(data)
        }

    private fun stored(key: String): ByteArray? = prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    private fun storePlainHash(pattern: String, iterations: Int): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("pattern_hash_v2", Base64.encodeToString(pbkdf2(pattern, salt, iterations), Base64.NO_WRAP))
            .putString("pattern_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .commit()
        return salt
    }

    private fun encryptCipher(): Cipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    @Test
    fun aNewPatternIsStoredAsTheKeystoreHmacOfALightStretch() {
        AppLockCredentials.setPattern("0147")

        val salt = stored("pattern_salt")!!
        assertArrayEquals(hmac(pbkdf2("0147", salt, 10_000)), stored("pattern_hash_v3"))
        assertNull("no offline-testable hash is left", stored("pattern_hash_v2"))
        assertTrue(AppLockCredentials.hasPattern())
        assertTrue(AppLockCredentials.verifyPattern("0147"))
        assertFalse(AppLockCredentials.verifyPattern("0148"))
        assertEquals(1, AppLockCredentials.getFailureCount())
    }

    @Test
    fun anotherMacKeyDoesNotVerifyThePattern() {
        AppLockCredentials.setPattern("0147")
        macKey = newMacKey()
        PatternMacKey.forgetCachedKey()

        assertFalse(AppLockCredentials.verifyPattern("0147"))
    }

    @Test
    fun withoutAKeystoreThePatternIsStoredAsThePlainHash() {
        PatternMacKey.loader = { throw java.security.KeyStoreException("no AndroidKeyStore") }

        AppLockCredentials.setPattern("0147")

        val salt = stored("pattern_salt")!!
        assertArrayEquals(pbkdf2("0147", salt, 200_000), stored("pattern_hash_v2"))
        assertNull(stored("pattern_hash_v3"))
        assertTrue(AppLockCredentials.verifyPattern("0147"))
    }

    @Test
    fun aPlainHashMovesToTheFastVerifierOnTheNextUnlock() {
        val salt = storePlainHash("0147", 200_000)

        assertFalse(AppLockCredentials.verifyPattern("0148"))
        assertNull("a wrong pattern migrates nothing", stored("pattern_hash_v3"))

        assertTrue(AppLockCredentials.verifyPattern("0147"))
        assertArrayEquals(hmac(pbkdf2("0147", salt, 10_000)), stored("pattern_hash_v3"))
        assertNull(stored("pattern_hash_v2"))
        assertTrue(AppLockCredentials.verifyPattern("0147"))
        assertFalse(AppLockCredentials.verifyPattern("0148"))
    }

    @Test
    fun aLegacy100kHashMovesToTheFastVerifierToo() {
        val salt = storePlainHash("0147", 100_000)

        assertTrue(AppLockCredentials.verifyPattern("0147"))

        assertArrayEquals(hmac(pbkdf2("0147", salt, 10_000)), stored("pattern_hash_v3"))
        assertNull(stored("pattern_hash_v2"))
    }

    @Test
    fun aFingerprintBoundPlainHashIsKeptAsIs() {
        val salt = storePlainHash("0147", 200_000)
        prefs.edit().putString("pattern_encrypted", "YmxvYg==").commit()

        assertTrue(AppLockCredentials.verifyPattern("0147"))

        assertArrayEquals(pbkdf2("0147", salt, 200_000), stored("pattern_hash_v2"))
        assertNull(stored("pattern_hash_v3"))
    }

    @Test
    fun aLostMacKeyIsNotAFailedAttemptButUnverifiable() {
        AppLockCredentials.setPattern("0147")
        macKey = null
        PatternMacKey.forgetCachedKey()

        assertFalse(AppLockCredentials.verifyPattern("0147"))
        assertEquals(0, AppLockCredentials.getFailureCount())
        assertTrue(AppLockCredentials.isPatternVerifierLost())
    }

    @Test
    fun aKeystoreErrorIsNotALostKey() {
        AppLockCredentials.setPattern("0147")
        PatternMacKey.loader = { throw java.security.KeyStoreException("keystore busy") }
        PatternMacKey.forgetCachedKey()

        assertFalse(AppLockCredentials.isPatternVerifierLost())
    }

    @Test
    fun theLockScreenOffersTheResetWhenTheMacKeyIsLost() = runTest(UnconfinedTestDispatcher()) {
        AppLockCredentials.setPattern("0147")
        macKey = null
        PatternMacKey.forgetCachedKey()
        val vm = SecurityViewModel(kotlinx.coroutines.Dispatchers.Unconfined)
        val received = mutableListOf<SecurityUiEvent>()
        backgroundScope.launch { vm.uiEvent.collect { received += it } }

        vm.onPatternDetected("0147")

        assertEquals(listOf<SecurityUiEvent>(SecurityUiEvent.PatternKeyLost), received)
    }

    @Test
    fun aFingerprintBoundPatternUsesTheFastVerifier() {
        AppLockCredentials.setPatternWithCipher("0147", encryptCipher())

        val salt = stored("pattern_salt")!!
        assertArrayEquals(hmac(pbkdf2("0147", salt, 10_000)), stored("pattern_hash_v3"))
        assertNull(stored("pattern_hash_v2"))
        assertTrue(AppLockCredentials.canVerifyWithoutKeystore())
        assertTrue(AppLockCredentials.verifyPattern("0147"))
    }

    @Test
    fun theFingerprintChecksWorkOnTheFastVerifier() {
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        AppLockCredentials.setPatternWithCipher(
            "0147",
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes) },
        )
        fun decryptCipher() = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, aes, javax.crypto.spec.GCMParameterSpec(128, stored("pattern_iv")))
        }

        assertTrue(AppLockCredentials.verifyFingerprintCipher(decryptCipher()))
        assertTrue(AppLockCredentials.verifyPatternWithCipher("0147", decryptCipher()))
        assertFalse(AppLockCredentials.verifyPatternWithCipher("0148", decryptCipher()))
    }

    @Test
    fun aFingerprintBlobThatIsNotTheStoredVerifierIsRefused() {
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        AppLockCredentials.setPatternWithCipher(
            "0147",
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes) },
        )
        // Set again without the fingerprint: a new verifier, the old blob put back.
        val blob = prefs.getString("pattern_encrypted", null)
        val iv = prefs.getString("pattern_iv", null)
        AppLockCredentials.setPattern("0147")
        prefs.edit().putString("pattern_encrypted", blob).putString("pattern_iv", iv).commit()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, aes, javax.crypto.spec.GCMParameterSpec(128, stored("pattern_iv")))
        }
        assertFalse(AppLockCredentials.verifyFingerprintCipher(cipher))
    }

    @Test
    fun clearingThePatternDeletesTheMacKey() {
        AppLockCredentials.setPattern("0147")

        AppLockCredentials.setPattern(null)

        assertNull(macKey)
        assertFalse(AppLockCredentials.hasPattern())
    }
}
