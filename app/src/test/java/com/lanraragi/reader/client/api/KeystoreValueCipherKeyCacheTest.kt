package com.lanraragi.reader.client.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Audit 06e PERF-01: [KeystoreValueCipher] fetches its key from the KeyStore
 * once instead of per value, and forgets it on [KeystoreValueCipher.deleteKey]
 * and on any crypto failure, so an invalidated or replaced key is fetched
 * again instead of being reused. The KeyStore is replaced by counting seams
 * (Robolectric has no AndroidKeyStore).
 */
class KeystoreValueCipherKeyCacheTest {

    private class CountingKeySource(private val keys: List<SecretKey>) {
        var loads = 0
        var removes = 0

        fun load(alias: String): SecretKey {
            assertEquals(ALIAS, alias)
            return keys[minOf(loads++, keys.size - 1)]
        }

        fun remove(alias: String) {
            assertEquals(ALIAS, alias)
            removes++
        }
    }

    private fun cipherWith(source: CountingKeySource) =
        KeystoreValueCipher(ALIAS, { source.load(it) }, { source.remove(it) })

    @Test
    fun many_values_fetch_the_key_once() {
        val source = CountingKeySource(listOf(newKey()))
        val cipher = cipherWith(source)

        repeat(20) { i ->
            val plain = "value-$i".toByteArray()
            assertArrayEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
        }

        assertEquals("40 crypto calls must cost one KeyStore lookup", 1, source.loads)
    }

    @Test
    fun deleteKey_drops_the_cached_key() {
        val source = CountingKeySource(listOf(newKey(), newKey()))
        val cipher = cipherWith(source)
        val oldBlob = cipher.encrypt("old".toByteArray())

        cipher.deleteKey()

        assertEquals(1, source.removes)
        // The next value uses the freshly fetched key: the old blob no longer opens.
        assertArrayEquals("new".toByteArray(), cipher.decrypt(cipher.encrypt("new".toByteArray())))
        assertEquals(2, source.loads)
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(oldBlob) }
    }

    @Test
    fun a_failing_key_is_fetched_again_on_the_next_call() {
        // A key the cipher refuses stands in for KeyPermanentlyInvalidatedException:
        // init throws a GeneralSecurityException.
        val broken = SecretKeySpec(ByteArray(5), "AES")
        val source = CountingKeySource(listOf(broken, newKey()))
        val cipher = cipherWith(source)

        assertThrows(GeneralSecurityException::class.java) { cipher.encrypt("x".toByteArray()) }
        // Failure semantics are unchanged: the error still reaches the caller,
        // and the next call looks the key up again instead of reusing the broken one.
        assertArrayEquals("y".toByteArray(), cipher.decrypt(cipher.encrypt("y".toByteArray())))
        assertEquals(2, source.loads)
    }

    @Test
    fun a_failing_key_lookup_is_not_cached() {
        var attempts = 0
        val good = newKey()
        val cipher = KeystoreValueCipher(ALIAS, {
            attempts++
            if (attempts == 1) throw GeneralSecurityException("KeyStore busy")
            good
        }, { })

        assertThrows(GeneralSecurityException::class.java) { cipher.encrypt("x".toByteArray()) }
        assertArrayEquals("x".toByteArray(), cipher.decrypt(cipher.encrypt("x".toByteArray())))
        assertEquals(2, attempts)
    }

    private companion object {
        const val ALIAS = "test_alias"

        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
