package com.lanraragi.reader.client.api

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException

/**
 * Audit 06e PERF-01: [KeystoreSecurePrefs] keeps decrypted values, so the
 * server URL / API key read per list entry and per request cost one
 * decrypt, and never serves a value a write has replaced — whichever path
 * wrote it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class KeystoreSecurePrefsValueCacheTest {

    private class CountingCipher(private val inner: ValueCipher = InMemoryValueCipher()) : ValueCipher {
        var decrypts = 0
        var failNextDecrypt = false

        override fun encrypt(plain: ByteArray): ByteArray = inner.encrypt(plain)

        override fun decrypt(blob: ByteArray): ByteArray {
            decrypts++
            if (failNextDecrypt) {
                failNextDecrypt = false
                throw GeneralSecurityException("transient KeyStore failure")
            }
            return inner.decrypt(blob)
        }
    }

    private lateinit var backing: SharedPreferences
    private val cipher = CountingCipher()

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        backing = ctx.getSharedPreferences("secure_prefs_cache_test", Context.MODE_PRIVATE)
        backing.edit().clear().commit()
    }

    private fun openStore(): KeystoreSecurePrefs = KeystoreSecurePrefs.open(backing, cipher)

    @Test
    fun repeated_reads_of_one_key_cost_one_decrypt() {
        val prefs = openStore()
        prefs.edit().putString("server_url", "http://h:3000").putLong("active_profile_id", 3L).commit()
        cipher.decrypts = 0

        repeat(100) {
            assertEquals("http://h:3000", prefs.getString("server_url", null))
            assertEquals(3L, prefs.getLong("active_profile_id", 0L))
        }

        assertEquals("100 reads of each of two keys must decrypt each once", 2, cipher.decrypts)
    }

    @Test
    fun commit_and_apply_replace_the_cached_value() {
        val prefs = openStore()
        prefs.edit().putString("server_url", "http://a:3000").commit()
        assertEquals("http://a:3000", prefs.getString("server_url", null))

        prefs.edit().putString("server_url", "http://b:3000").commit()
        assertEquals("http://b:3000", prefs.getString("server_url", null))

        prefs.edit().putString("server_url", "http://c:3000").apply()
        assertEquals("http://c:3000", prefs.getString("server_url", null))
    }

    @Test
    fun remove_and_clear_end_the_cached_value() {
        val prefs = openStore()
        prefs.edit().putString("api_key", "k1").putString("server_url", "http://a:3000").commit()
        assertEquals("k1", prefs.getString("api_key", null))
        assertEquals("http://a:3000", prefs.getString("server_url", null))

        prefs.edit().remove("api_key").commit()
        assertNull(prefs.getString("api_key", null))
        assertFalse("a removed secret must not stay in memory", "api_key" in prefs.cachedKeysForTesting())

        prefs.edit().clear().commit()
        assertNull(prefs.getString("server_url", null))
        assertEquals("clear() must drop every held value", emptySet<String>(), prefs.cachedKeysForTesting())
    }

    @Test
    fun a_write_through_another_path_is_never_served_stale() {
        val prefs = openStore()
        prefs.edit().putString("server_url", "http://old:3000").commit()
        assertEquals("http://old:3000", prefs.getString("server_url", null))

        // Another store over the same file (migration, a re-open after a
        // reset) writes the value: this instance's editor never saw it.
        openStore().edit().putString("server_url", "http://new:3000").commit()
        assertEquals("http://new:3000", prefs.getString("server_url", null))

        // The file itself loses the value (reset, backup restore).
        backing.edit().remove("server_url").commit()
        assertNull(prefs.getString("server_url", null))
    }

    @Test
    fun a_failed_decrypt_is_not_cached() {
        val prefs = openStore()
        prefs.edit().putString("api_key", "k1").commit()
        cipher.failNextDecrypt = true

        assertNull("a value that does not decrypt reads as the default", prefs.getString("api_key", null))
        assertEquals("the next read tries again", "k1", prefs.getString("api_key", null))
    }

    @Test
    fun getAll_shares_the_cache() {
        val prefs = openStore()
        prefs.edit().putString("a", "1").putInt("b", 2).commit()
        cipher.decrypts = 0

        assertEquals(mapOf("a" to "1", "b" to 2), prefs.all)
        assertEquals("1", prefs.getString("a", null))
        assertEquals(2, prefs.getInt("b", 0))

        assertEquals(2, cipher.decrypts)
    }
}
