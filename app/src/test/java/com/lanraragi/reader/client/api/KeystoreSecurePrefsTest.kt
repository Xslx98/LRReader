package com.lanraragi.reader.client.api

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Audit C49 / SEC-17: the replacement for EncryptedSharedPreferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class KeystoreSecurePrefsTest {

    /** In-memory AES-GCM: Robolectric has no AndroidKeyStore. */
    private class TestCipher(private val key: SecretKey = newKey()) : ValueCipher {
        override fun encrypt(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return ByteBuffer.allocate(12 + plain.size + 16).put(iv).put(c.doFinal(plain)).array()
        }

        override fun decrypt(blob: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }

        companion object {
            fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private lateinit var backing: SharedPreferences
    private val cipher = TestCipher()

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        backing = ctx.getSharedPreferences("secure_prefs_test", Context.MODE_PRIVATE)
        backing.edit().clear().commit()
    }

    @Test
    fun every_type_round_trips() {
        val prefs = KeystoreSecurePrefs.open(backing, cipher)
        prefs.edit()
            .putString("s", "api-key-123")
            .putLong("l", 42L)
            .putInt("i", 7)
            .putBoolean("b", true)
            .putFloat("f", 1.5f)
            .putStringSet("set", mutableSetOf("a", "b"))
            .commit()

        val reopened = KeystoreSecurePrefs.open(backing, cipher)
        assertEquals("api-key-123", reopened.getString("s", null))
        assertEquals(42L, reopened.getLong("l", 0L))
        assertEquals(7, reopened.getInt("i", 0))
        assertTrue(reopened.getBoolean("b", false))
        assertEquals(1.5f, reopened.getFloat("f", 0f))
        assertEquals(setOf("a", "b"), reopened.getStringSet("set", null))
        assertEquals(setOf("s", "l", "i", "b", "f", "set"), reopened.all.keys)
    }

    @Test
    fun values_are_not_stored_in_plaintext() {
        KeystoreSecurePrefs.open(backing, cipher).edit().putString("api_key", "super-secret").commit()
        val raw = backing.all.values.joinToString()
        assertFalse(raw.contains("super-secret"))
        assertTrue(backing.contains("api_key"))
    }

    @Test
    fun a_different_key_fails_at_open_instead_of_reading_defaults() {
        KeystoreSecurePrefs.open(backing, cipher).edit().putString("api_key", "k").commit()
        assertThrows(GeneralSecurityException::class.java) {
            KeystoreSecurePrefs.open(backing, TestCipher())
        }
    }

    @Test
    fun foreign_content_without_canary_is_refused() {
        backing.edit().putString("api_key", "plaintext").commit()
        assertThrows(GeneralSecurityException::class.java) {
            KeystoreSecurePrefs.open(backing, cipher)
        }
    }

    @Test
    fun remove_and_clear_keep_the_store_openable() {
        val prefs = KeystoreSecurePrefs.open(backing, cipher)
        prefs.edit().putString("a", "1").putString("b", "2").commit()
        prefs.edit().remove("a").commit()
        assertNull(prefs.getString("a", null))
        assertFalse(prefs.contains("a"))

        // Android semantics: clear() runs before this editor's own puts —
        // including a re-put of a key that existed before.
        prefs.edit().putString("d", "4").commit()
        prefs.edit().clear().putString("c", "3").putString("d", "4b").commit()
        val reopened = KeystoreSecurePrefs.open(backing, cipher)
        assertNull(reopened.getString("b", null))
        assertEquals("3", reopened.getString("c", null))
        assertEquals("4b", reopened.getString("d", null))
        assertEquals(setOf("c", "d"), reopened.all.keys)
    }

    @Test
    fun type_mismatch_reads_the_default() {
        val prefs = KeystoreSecurePrefs.open(backing, cipher)
        prefs.edit().putString("id", "5").commit()
        assertEquals(0L, prefs.getLong("id", 0L))
    }

    @Test
    fun legacy_entries_migrate_with_their_types() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val legacy = ctx.getSharedPreferences("legacy_test", Context.MODE_PRIVATE)
        legacy.edit().clear()
            .putString("server_url", "http://192.168.1.10:3000")
            .putString("api_key_profile_3", "k3")
            .putLong("active_profile_id", 3L)
            .putBoolean("allow_cleartext", true)
            .commit()
        val target = KeystoreSecurePrefs.open(backing, cipher)

        assertTrue(SecurePrefsMigration.copyAll(legacy, target))

        val reopened = KeystoreSecurePrefs.open(backing, cipher)
        assertEquals("http://192.168.1.10:3000", reopened.getString("server_url", null))
        assertEquals("k3", reopened.getString("api_key_profile_3", null))
        assertEquals(3L, reopened.getLong("active_profile_id", 0L))
        assertTrue(reopened.getBoolean("allow_cleartext", false))
    }
}
