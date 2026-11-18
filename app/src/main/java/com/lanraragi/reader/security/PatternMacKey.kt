package com.lanraragi.reader.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.lanraragi.reader.server.CredentialStoreLayout.KEYSTORE_ALIAS_PATTERN_MAC
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The key that makes the app-lock pattern verifier fast and still hard to
 * brute-force: a non-exportable AndroidKeyStore HMAC-SHA256 key, usable
 * without user authentication. The stored verifier is this key's HMAC of a
 * light PBKDF2 stretch of the pattern, so a copy of the store gives an
 * attacker nothing to test guesses against without the phone's keystore,
 * and checking a pattern on the phone costs milliseconds instead of a
 * 200,000-round PBKDF2.
 *
 * [loader] and [remover] are seams for tests (Robolectric has no
 * AndroidKeyStore). Without a keystore the loader throws, and the pattern
 * is stored as the older plain PBKDF2 hash.
 */
internal object PatternMacKey {

    private const val TAG = "PatternMacKey"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val ALGORITHM = "HmacSHA256"

    /** Loads the key, creating it first when [create]; null when it does not exist. */
    @Volatile
    internal var loader: (create: Boolean) -> SecretKey? = ::loadKeystoreKey

    @Volatile
    internal var remover: () -> Unit = ::deleteKeystoreKey

    /** Fetched once: every keystore lookup is several binder round trips. */
    @Volatile
    private var cached: SecretKey? = null

    /**
     * HMAC of [data] under the key, or null when the key does not exist
     * (and [create] is false).
     */
    @Throws(GeneralSecurityException::class)
    fun mac(data: ByteArray, create: Boolean): ByteArray? {
        val key = cached ?: loader(create)?.also { cached = it } ?: return null
        try {
            return Mac.getInstance(ALGORITHM).run {
                init(key)
                doFinal(data)
            }
        } catch (e: GeneralSecurityException) {
            cached = null
            throw e
        } catch (e: ProviderException) {
            cached = null
            throw GeneralSecurityException(e)
        }
    }

    /** False when the key is gone for good (a keystore error is not an answer: true). */
    fun exists(): Boolean {
        if (cached != null) return true
        return try {
            loader(false) != null
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Pattern MAC key lookup failed", e)
            true
        } catch (e: ProviderException) {
            Log.w(TAG, "Pattern MAC key lookup failed", e)
            true
        }
    }

    fun delete() {
        cached = null
        try {
            remover()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete the pattern MAC key", e)
        }
    }

    /** Tests: drop the cached handle after swapping [loader]. */
    internal fun forgetCachedKey() {
        cached = null
    }

    private fun loadKeystoreKey(create: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS_PATTERN_MAC, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEY_STORE)
        generator.init(KeyGenParameterSpec.Builder(KEYSTORE_ALIAS_PATTERN_MAC, KeyProperties.PURPOSE_SIGN).build())
        return generator.generateKey()
    }

    private fun deleteKeystoreKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS_PATTERN_MAC)) keyStore.deleteEntry(KEYSTORE_ALIAS_PATTERN_MAC)
    }
}
