package com.lanraragi.reader.client.api

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts one stored value. Production uses an AndroidKeyStore
 * key ([KeystoreValueCipher]); tests use an in-memory key, since Robolectric
 * has no AndroidKeyStore.
 */
interface ValueCipher {
    @Throws(GeneralSecurityException::class)
    fun encrypt(plain: ByteArray): ByteArray

    @Throws(GeneralSecurityException::class)
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable AndroidKeyStore key (no user
 * authentication, random IV per value). Blob = 12-byte IV + ciphertext+tag.
 */
class KeystoreValueCipher(private val alias: String) : ValueCipher {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return ByteBuffer.allocate(iv.size + plain.size + TAG_BYTES)
            .put(iv).put(cipher.doFinal(plain)).array()
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < IV_BYTES + TAG_BYTES) throw GeneralSecurityException("Blob too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    /** Removes the key; every value encrypted with it becomes unreadable. */
    fun deleteKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/**
 * [SharedPreferences] whose values are encrypted with [cipher] and kept in
 * the plain preferences file [backing]; key names stay readable (they name
 * a slot, e.g. `api_key_profile_3`, never a secret). Replaces the deprecated
 * androidx.security EncryptedSharedPreferences (audit C49 / SEC-17).
 *
 * Each value is `base64(cipher(typeTag + payload))`. [open] decrypts a
 * canary first, so a lost or replaced key surfaces as a
 * [GeneralSecurityException] at open time — as EncryptedSharedPreferences
 * did — instead of as values that silently read back as defaults.
 *
 * Change listeners are not supported (nothing in the app registers one).
 */
class KeystoreSecurePrefs private constructor(
    private val backing: SharedPreferences,
    private val cipher: ValueCipher,
) : SharedPreferences {

    companion object {
        internal const val CANARY_KEY = "\u0000canary"
        private const val CANARY_VALUE = "lrr-secure-prefs-v1"
        private const val TAG = "KeystoreSecurePrefs"

        private const val T_STRING = 's'
        private const val T_INT = 'i'
        private const val T_LONG = 'l'
        private const val T_FLOAT = 'f'
        private const val T_BOOLEAN = 'b'
        private const val T_STRING_SET = 'S'
        private const val SET_SEPARATOR = '\u0000'

        /**
         * Opens the store, writing the canary on first use.
         * @throws GeneralSecurityException when existing values cannot be decrypted
         */
        @Throws(GeneralSecurityException::class)
        fun open(backing: SharedPreferences, cipher: ValueCipher): KeystoreSecurePrefs {
            val prefs = KeystoreSecurePrefs(backing, cipher)
            val canary = backing.getString(CANARY_KEY, null)
            if (canary == null) {
                if (backing.all.isNotEmpty()) throw GeneralSecurityException("Secure store has no canary")
                backing.edit().putString(CANARY_KEY, prefs.seal(T_STRING, CANARY_VALUE)).commit()
            } else if (prefs.unseal(canary)?.second != CANARY_VALUE) {
                throw GeneralSecurityException("Secure store canary does not decrypt")
            }
            return prefs
        }
    }

    private fun seal(type: Char, payload: String): String =
        Base64.encodeToString(cipher.encrypt("$type$payload".toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    /** (type, payload), or null when the value cannot be decrypted. */
    private fun unseal(stored: String): Pair<Char, String>? = try {
        val plain = String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        if (plain.isEmpty()) null else plain[0] to plain.substring(1)
    } catch (e: GeneralSecurityException) {
        android.util.Log.e(TAG, "Secure value does not decrypt", e)
        null
    } catch (e: IllegalArgumentException) {
        android.util.Log.e(TAG, "Secure value is not base64", e)
        null
    }

    private fun read(key: String, type: Char): String? {
        val stored = backing.getString(key, null) ?: return null
        val (t, payload) = unseal(stored) ?: return null
        return if (t == type) payload else null
    }

    override fun getAll(): Map<String, *> =
        backing.all.entries
            .filter { (key, stored) -> key != CANARY_KEY && stored is String }
            .mapNotNull { (key, stored) -> unseal(stored as String)?.let { (type, payload) -> key to decode(type, payload) } }
            .toMap()

    private fun decode(type: Char, payload: String): Any? = when (type) {
        T_STRING -> payload
        T_INT -> payload.toIntOrNull()
        T_LONG -> payload.toLongOrNull()
        T_FLOAT -> payload.toFloatOrNull()
        T_BOOLEAN -> payload == "1"
        T_STRING_SET -> if (payload.isEmpty()) emptySet() else payload.split(SET_SEPARATOR).toSet()
        else -> null
    }

    override fun getString(key: String, defValue: String?): String? = read(key, T_STRING) ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        read(key, T_STRING_SET)?.let { decode(T_STRING_SET, it) as Set<*> }
            ?.mapTo(HashSet()) { it as String } ?: defValues

    override fun getInt(key: String, defValue: Int): Int = read(key, T_INT)?.toIntOrNull() ?: defValue

    override fun getLong(key: String, defValue: Long): Long = read(key, T_LONG)?.toLongOrNull() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = read(key, T_FLOAT)?.toFloatOrNull() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        read(key, T_BOOLEAN)?.let { it == "1" } ?: defValue

    override fun contains(key: String): Boolean = key != CANARY_KEY && backing.contains(key)

    override fun edit(): SharedPreferences.Editor = Editor(backing.edit())

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = throw UnsupportedOperationException("KeystoreSecurePrefs has no change listeners")

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor(private val inner: SharedPreferences.Editor) : SharedPreferences.Editor {
        private var cleared = false

        /** Keys put or removed through this editor; clear() leaves the puts alone. */
        private val touched = HashSet<String>()

        private fun put(key: String, type: Char, payload: String): SharedPreferences.Editor {
            touched += key
            inner.putString(key, seal(type, payload))
            return this
        }

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            if (value == null) remove(key) else put(key, T_STRING, value)

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            if (values == null) remove(key) else put(key, T_STRING_SET, values.joinToString(SET_SEPARATOR.toString()))

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, T_INT, value.toString())

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, T_LONG, value.toString())

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            put(key, T_FLOAT, value.toString())

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            put(key, T_BOOLEAN, if (value) "1" else "0")

        override fun remove(key: String): SharedPreferences.Editor {
            if (key != CANARY_KEY) {
                touched += key
                inner.remove(key)
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            cleared = true
            return this
        }

        /**
         * clear() as Android applies it — before this editor's own puts — and
         * keeping the canary, so the store stays openable.
         */
        private fun applyClear() {
            if (!cleared) return
            for (key in backing.all.keys) if (key != CANARY_KEY && key !in touched) inner.remove(key)
        }

        override fun commit(): Boolean {
            applyClear()
            return inner.commit()
        }

        override fun apply() {
            applyClear()
            inner.apply()
        }
    }
}
