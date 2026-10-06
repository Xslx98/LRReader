package com.lanraragi.reader.client.api

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * In-memory AES-GCM for [LRRAuthManager.secureStoreCipher]: Robolectric has
 * no AndroidKeyStore, so without it the production init always fails to
 * open the store.
 */
internal class InMemoryValueCipher(
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
) : ValueCipher {

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
}
