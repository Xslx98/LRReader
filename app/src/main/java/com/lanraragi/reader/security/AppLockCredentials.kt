package com.lanraragi.reader.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.lanraragi.reader.server.CredentialStoreLayout.KEYSTORE_ALIAS_PATTERN
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_CONFIGURED_HINT
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_LEGACY_LOCK_REMOVED_NOTICE
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_LOCKOUT_BOOT
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_LOCKOUT_DURATION
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_LOCKOUT_START_ELAPSED
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_LOCK_ENABLED
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_ENCRYPTED
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_FAIL_COUNT
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_HASH_V2
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_IV
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_KEYSTORE_BOUND
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_LOCKOUT_UNTIL_LEGACY
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_SALT
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_SECURE_STORE_MIGRATED
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_STORE_OPEN_FAILURES
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_WAS_CONFIGURED
import com.lanraragi.reader.server.CredentialStoreLayout.SECURE_PREF_NAME
import com.lanraragi.reader.server.CredentialStoreLayout.hasPatternIn
import com.lanraragi.reader.server.LRRSecureStorageUnavailableException
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.server.SecureCredentialStore.SecureStorageState
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * The app lock's credentials: the pattern (PBKDF2 hash, optionally wrapped
 * by a fingerprint-bound KeyStore key), the failure lockout, the plain
 * lock-state mirror that lets the lock fail closed, and the last-resort
 * reset. Stored in [SecureCredentialStore] (layout in
 * `server.CredentialStoreLayout`); this object holds no state of its own.
 */
object AppLockCredentials {

    private const val TAG = "AppLockCredentials"
    private const val PBKDF2_ITERATIONS_V1 = 100_000  // Legacy, kept for migration
    private const val PBKDF2_ITERATIONS = 200_000
    private const val PBKDF2_KEY_BITS = 256
    private const val AES_GCM_TAG_BITS = 128
    private const val LOCKOUT_THRESHOLD_FIRST = 5

    /**
     * Lockout after the 5th, 6th, 7th, 8th and every later consecutive
     * failure. Escalating: a flat cap let brute force run at a steady rate.
     */
    private val LOCKOUT_DURATIONS_MS = longArrayOf(30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)

    private val store get() = SecureCredentialStore

    // ── Persistent failure lockout ──────────────────────────────────────

    /**
     * @return true if the pattern is currently locked out due to too many failed attempts.
     */
    @JvmStatic
    fun isLockedOut(): Boolean = getLockoutRemainingMs() > 0

    /**
     * @return the remaining lockout duration in milliseconds, or 0 if not locked out.
     *
     * Measured on the monotonic clock within one boot. After a reboot the
     * clock restarts, so the lockout is re-anchored and its full duration
     * runs again — rebooting never shortens it.
     */
    @JvmStatic
    fun getLockoutRemainingMs(): Long {
        store.awaitInit()
        val plain = store.plainPrefs ?: return 0L
        val duration = plain.getLong(KEY_LOCKOUT_DURATION, 0L)
        if (duration <= 0L) return 0L
        val now = store.clockMillis()
        val start = plain.getLong(KEY_LOCKOUT_START_ELAPSED, now)
        val boot = store.bootCount()
        if (plain.getInt(KEY_LOCKOUT_BOOT, boot) != boot || now < start) {
            plain.edit {
                putLong(KEY_LOCKOUT_START_ELAPSED, now)
                putInt(KEY_LOCKOUT_BOOT, boot)
            }
            return duration
        }
        return (duration - (now - start)).coerceAtLeast(0L)
    }

    /**
     * Record a failed pattern attempt. Increments the persistent counter and,
     * from the 5th consecutive failure on, starts an escalating lockout
     * (30 s, 1 min, 5 min, 15 min, then 1 h for every further failure).
     */
    @JvmStatic
    fun recordFailure() {
        store.awaitInit()
        val plain = store.plainPrefs ?: return
        val count = plain.getInt(KEY_PATTERN_FAIL_COUNT, 0) + 1
        plain.edit {
            putInt(KEY_PATTERN_FAIL_COUNT, count)
            remove(KEY_PATTERN_LOCKOUT_UNTIL_LEGACY)
            if (count >= LOCKOUT_THRESHOLD_FIRST) {
                val level = (count - LOCKOUT_THRESHOLD_FIRST).coerceAtMost(LOCKOUT_DURATIONS_MS.size - 1)
                putLong(KEY_LOCKOUT_DURATION, LOCKOUT_DURATIONS_MS[level])
                putLong(KEY_LOCKOUT_START_ELAPSED, store.clockMillis())
                putInt(KEY_LOCKOUT_BOOT, store.bootCount())
            }
        }
    }

    /**
     * Reset the failure counter and lockout timestamp. Called on successful verification.
     */
    @JvmStatic
    fun resetFailures() {
        store.awaitInit()
        val plain = store.plainPrefs ?: return
        plain.edit {
            remove(KEY_PATTERN_FAIL_COUNT)
            remove(KEY_PATTERN_LOCKOUT_UNTIL_LEGACY)
            remove(KEY_LOCKOUT_DURATION)
            remove(KEY_LOCKOUT_START_ELAPSED)
            remove(KEY_LOCKOUT_BOOT)
        }
    }

    /**
     * @return the current failure count (for UI display).
     */
    @JvmStatic
    fun getFailureCount(): Int {
        store.awaitInit()
        return store.plainPrefs?.getInt(KEY_PATTERN_FAIL_COUNT, 0) ?: 0
    }

    // ── KeyStore-bound AES-GCM pattern wrapping ─────────────────────────

    /**
     * @return true if the stored pattern hash is bound to Android KeyStore via AES-GCM.
     * When true, [verifyPatternWithCipher] must be used instead of [verifyPattern].
     */
    @JvmStatic
    fun isPatternKeystoreBound(): Boolean {
        store.awaitInit()
        return store.plainPrefs?.getBoolean(KEY_PATTERN_KEYSTORE_BOUND, false) == true
    }

    /**
     * Generate or retrieve the KeyStore-backed AES key for pattern hash encryption.
     * The key requires user authentication via BiometricPrompt to use.
     *
     * @throws GeneralSecurityException if KeyStore operations fail
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun generatePatternKeystoreKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(KEYSTORE_ALIAS_PATTERN)) return

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        keyGen.init(patternKeySpec())
        keyGen.generateKey()
    }

    /**
     * The pattern key's spec: AES-GCM, usable only through a strong-biometric
     * BiometricPrompt CryptoObject, once per authentication.
     *
     * `setUserAuthenticationParameters` exists from API 30 only; on Android
     * 9/10 calling it threw NoSuchMethodError, an Error the caller's
     * fallback does not catch, so setting a pattern crashed (audit 06d
     * STAB-01). Below API 30 a validity of -1 is the same rule: per-use
     * authentication that only a biometric can satisfy, and a key that the
     * CryptoObject flow unlocks (BiometricPrompt exists from API 28).
     */
    @JvmStatic
    internal fun patternKeySpec(): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS_PATTERN,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return builder.build()
    }

    /**
     * Whether the pattern can be checked without the keystore key, i.e. a
     * plain PBKDF2 hash is stored. False only for keystore-bound patterns
     * saved before that hash was kept.
     */
    @JvmStatic
    fun canVerifyWithoutKeystore(): Boolean {
        store.awaitInit()
        return store.prefs?.contains(KEY_PATTERN_HASH_V2) == true
    }

    /**
     * Drop the keystore binding (after the key was invalidated), keeping the
     * pattern itself: it stays verifiable through the plain PBKDF2 hash.
     */
    @JvmStatic
    fun unbindPatternFromKeystore() {
        store.awaitInit()
        store.prefs?.edit {
            remove(KEY_PATTERN_ENCRYPTED)
            remove(KEY_PATTERN_IV)
        }
        store.plainPrefs?.edit { putBoolean(KEY_PATTERN_KEYSTORE_BOUND, false) }
        deletePatternKeystoreKey()
    }

    /**
     * Delete the KeyStore-backed AES key for pattern hash encryption.
     * Called when clearing the pattern or when falling back to PBKDF2-only.
     */
    @JvmStatic
    fun deletePatternKeystoreKey() = store.deletePatternKeystoreKey()

    /**
     * Create a [Cipher] in ENCRYPT mode initialized with the KeyStore-backed AES key.
     * Must be called to create a CryptoObject for BiometricPrompt during [setPattern].
     *
     * @throws GeneralSecurityException if the KeyStore key is unavailable
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun getEncryptCipher(): Cipher {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val key = keyStore.getKey(KEYSTORE_ALIAS_PATTERN, null)
            ?: throw GeneralSecurityException("Pattern KeyStore key not found")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * Create a [Cipher] in DECRYPT mode initialized with the KeyStore-backed AES key
     * and the IV stored alongside the encrypted pattern hash.
     * Must be called to create a CryptoObject for BiometricPrompt during [verifyPatternWithCipher].
     *
     * @throws GeneralSecurityException if the KeyStore key or stored IV is unavailable
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun getDecryptCipher(): Cipher {
        store.awaitInit()
        val prefs = store.prefs ?: throw GeneralSecurityException("Secure storage unavailable")
        val ivStr = prefs.getString(KEY_PATTERN_IV, null)
            ?: throw PatternKeyMissingException("Pattern IV not found")
        val iv = Base64.decode(ivStr, Base64.NO_WRAP)
        val key = store.patternKeyLoader()
            ?: throw PatternKeyMissingException("Pattern KeyStore key not found")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // A key invalidated by a new fingerprint enrolment throws
        // KeyPermanentlyInvalidatedException here.
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        return cipher
    }

    /** The pattern key or its IV is gone for good: no fingerprint proof is possible until the pattern is set again. */
    class PatternKeyMissingException(message: String) : GeneralSecurityException(message)

    /**
     * Whether [authenticatedCipher] — the CryptoObject cipher returned by a
     * successful fingerprint BiometricPrompt — really is the pattern key,
     * unlocked by that authentication (audit 2026-10-06e SEC-01). The prompt's
     * success callback alone proves only that *some* enrolled finger matched,
     * including one added after the lock was set by anyone who knows the
     * device PIN. The key is created with `setUserAuthenticationRequired`
     * and the default `setInvalidatedByBiometricEnrollment(true)`, so after
     * such an enrolment it cannot be initialised at all, and without the
     * biometric it cannot be used: decrypting the stored blob (GCM checks
     * its tag, so a wrong key fails) is the proof.
     *
     * Scope (audit SEC-02): this binds the *fingerprint* unlock only. The
     * pattern itself is also stored as a plain PBKDF2 hash beside the
     * encrypted copy, so the binding adds nothing against an attacker who
     * can read the secure store; it is not a second factor for the pattern.
     */
    @JvmStatic
    fun verifyFingerprintCipher(authenticatedCipher: Cipher?): Boolean {
        if (authenticatedCipher == null) return false
        store.awaitInit()
        val prefs = store.prefs ?: return false
        val encryptedStr = prefs.getString(KEY_PATTERN_ENCRYPTED, null) ?: return false
        return try {
            val decrypted = authenticatedCipher.doFinal(Base64.decode(encryptedStr, Base64.NO_WRAP))
            val plainStr = prefs.getString(KEY_PATTERN_HASH_V2, null)
            plainStr == null || MessageDigest.isEqual(decrypted, Base64.decode(plainStr, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.e(TAG, "Fingerprint cipher did not decrypt the pattern blob", e)
            false
        }
    }

    // ── App-lock pattern (PBKDF2WithHmacSHA256 + optional KeyStore AES-GCM) ──

    /** @return true if an app-lock pattern has been stored. */
    @JvmStatic
    fun hasPattern(): Boolean {
        store.awaitInit()
        val prefs = store.prefs ?: return false
        return hasPatternIn(prefs)
    }

    /**
     * Whether an app lock is set, from the plain mirror — true even when the
     * secure store (and so the pattern itself) is unreadable. Null when the
     * mirror has not been written yet.
     */
    @JvmStatic
    fun lockEnabledHint(): Boolean? {
        val plain = store.fastPlainPrefs() ?: return null
        return if (plain.contains(KEY_LOCK_ENABLED)) plain.getBoolean(KEY_LOCK_ENABLED, false) else null
    }

    /**
     * Whether an app lock is set, FAILING CLOSED: true whenever a lock
     * cannot be ruled out (audit 2026-10-06c SEC-01). Reads the plain mirror
     * ([lockEnabledHint]). Without it — first launch after upgrading from a
     * version that never wrote it (<= v1.26), when the store is slow or
     * cannot be opened — it decides from what is readable without the
     * keystore (see [patternEvidenceWithoutKeystore]) and writes the mirror.
     * Only a legacy store that is still to be migrated cannot be read that
     * way: the store gets its bounded chance to open (which writes the
     * mirror), and if it does not, the answer is "locked". The lock screen
     * then offers its retry and, once the store failed, its reset, and lets
     * the user in as soon as the store opens without a pattern.
     *
     * False is therefore always a definite answer, which AppLockGate
     * may keep for the whole foreground session.
     */
    @JvmStatic
    fun isLockSetOrUnknown(): Boolean {
        val hint = lockEnabledHint()
        if (hint != null) return hint
        val evidence = patternEvidenceWithoutKeystore()
        if (evidence != null) {
            rememberLockState(evidence)
            return evidence
        }
        store.awaitInit()
        return lockEnabledHint() ?: true
    }

    /**
     * Whether a pattern is stored, from plaintext only: true / false, or null
     * when the legacy EncryptedSharedPreferences store (whose key names are
     * encrypted) has not been handled yet. The legacy check comes first:
     * init settles the legacy store's lock (see
     * SecureCredentialStore.abandonLegacyStore) before it marks itself done
     * and deletes the legacy file, so once the legacy store is seen as gone
     * the slots read next are final.
     */
    private fun patternEvidenceWithoutKeystore(): Boolean? {
        val plain = store.fastPlainPrefs()
        val context = store.appContext
        val legacyPending = context != null &&
            plain?.getBoolean(KEY_SECURE_STORE_MIGRATED, false) != true &&
            store.legacyStoreFile(context).exists()
        // Written with the pattern and removed with it by every version since v1.12.4.
        val boundFlag = plain?.contains(KEY_PATTERN_KEYSTORE_BOUND) == true
        // KeystoreSecurePrefs keeps its key names in plaintext.
        val opened = store.prefs
        val inStore = (opened != null && hasPatternIn(opened)) ||
            (context != null && hasPatternIn(context.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)))
        return when {
            boundFlag || inStore -> true
            legacyPending -> null
            else -> false
        }
    }

    /**
     * Write the lock mirror decided without the keystore, unless the store's
     * init (the source of truth) wrote it meanwhile.
     */
    private fun rememberLockState(locked: Boolean) {
        val plain = store.fastPlainPrefs()
        if (plain == null) return
        synchronized(store.lockMirrorWrite) {
            if (!plain.contains(KEY_LOCK_ENABLED)) plain.edit { putBoolean(KEY_LOCK_ENABLED, locked) }
        }
    }

    /**
     * Whether the "credentials unavailable" prompts outside the lock screen
     * may offer [resetAppLockAndCredentials] (audit 2026-10-06b REL-01): the
     * store finished opening and failed (never while it is still starting),
     * it also failed on the launch before (a KeyStore error that heals by
     * the next launch must not cost the user every API key, audit 06c
     * N-new-1), and no app lock is set. A store copied from another phone,
     * or one whose keystore key was lost, never becomes readable again, and
     * a user without a lock never sees the lock screen's reset. With a lock
     * set, the lock screen owns the reset.
     */
    @JvmStatic
    fun canOfferCredentialReset(): Boolean =
        store.secureStorageState() == SecureStorageState.UNAVAILABLE &&
            store.countStoreFailureShown() >= SecureCredentialStore.RESET_OFFER_MIN_FAILED_LAUNCHES &&
            !isLockSetOrUnknown()

    /**
     * Whether the lock screen's "store unavailable" prompt may offer the
     * reset: the same two-launch rule as [canOfferCredentialReset] (audit
     * 2026-10-06d SEC-01). On the first failed launch it offers only a
     * retry, as the failure may heal by itself.
     */
    @JvmStatic
    fun canOfferLockScreenReset(): Boolean =
        store.secureStorageState() == SecureStorageState.UNAVAILABLE &&
            store.countStoreFailureShown() >= SecureCredentialStore.RESET_OFFER_MIN_FAILED_LAUNCHES

    /**
     * Whether an app lock is set, from what is readable without the
     * keystore: true / false, or null when that cannot be told (a legacy
     * store not yet migrated and no mirror). Unlike [isLockSetOrUnknown]
     * it does not fail closed; for wording only, never for gating.
     */
    @JvmStatic
    fun lockStateWithoutKeystore(): Boolean? = lockEnabledHint() ?: patternEvidenceWithoutKeystore()

    /**
     * True after init dropped an app lock saved as the pre-PBKDF2 SHA-256
     * hash (audit 2026-10-06d SEC-08): it cannot be checked, so the app now
     * opens unlocked, and the user must be told to set a pattern again.
     * Stays true until [clearLegacyLockRemovedNotice]: the notice is cleared
     * when the user answers it, not when it is shown, so a rotation or
     * process death before the answer shows it again (audit 06e).
     */
    @JvmStatic
    fun isLegacyLockRemovedNoticePending(): Boolean {
        store.awaitInit()
        return store.fastPlainPrefs()?.getBoolean(KEY_LEGACY_LOCK_REMOVED_NOTICE, false) == true
    }

    /** The user answered the legacy-lock notice: do not show it again. */
    @JvmStatic
    fun clearLegacyLockRemovedNotice() {
        store.fastPlainPrefs()?.edit { remove(KEY_LEGACY_LOCK_REMOVED_NOTICE) }
    }

    /**
     * Last resort when the secure store is unreadable: drop the encrypted
     * store (pattern, API keys, server URL) and its master key, and clear
     * the lock state, so the next process start begins unlocked with no
     * saved credentials. The caller restarts the process.
     */
    @JvmStatic
    fun resetAppLockAndCredentials(context: Context) {
        store.wipe(context)
        deletePatternKeystoreKey()
        store.fastPlainPrefs()?.edit(commit = true) {
            putBoolean(KEY_LOCK_ENABLED, false)
            putBoolean(KEY_CONFIGURED_HINT, false)
            remove(KEY_WAS_CONFIGURED)
            remove(KEY_STORE_OPEN_FAILURES)
            remove(KEY_PATTERN_KEYSTORE_BOUND)
            remove(KEY_PATTERN_FAIL_COUNT)
            remove(KEY_PATTERN_LOCKOUT_UNTIL_LEGACY)
            remove(KEY_LOCKOUT_DURATION)
            remove(KEY_LOCKOUT_START_ELAPSED)
            remove(KEY_LOCKOUT_BOOT)
        }
    }

    /**
     * Hash [pattern] with PBKDF2WithHmacSHA256 (200K iterations) and persist to encrypted prefs.
     * Pass null or empty string to clear the pattern.
     *
     * For PBKDF2-only mode (no biometrics), stores the hash directly.
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setPattern(pattern: String?) {
        val prefs = store.requireSecurePrefs("setPattern")
        if (pattern.isNullOrEmpty()) {
            prefs.edit {
                remove(KEY_PATTERN_HASH_V2)
                remove(KEY_PATTERN_SALT)
                remove(KEY_PATTERN_ENCRYPTED)
                remove(KEY_PATTERN_IV)
            }
            store.plainPrefs?.edit {
                remove(KEY_PATTERN_KEYSTORE_BOUND)
                putBoolean(KEY_LOCK_ENABLED, false)
            }
            deletePatternKeystoreKey()
            resetFailures()
            return
        }
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val patChars = pattern.toCharArray()
        val spec = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val hash = factory.generateSecret(spec).encoded
            prefs.edit {
                putString(KEY_PATTERN_HASH_V2, Base64.encodeToString(hash, Base64.NO_WRAP))
                putString(KEY_PATTERN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                remove(KEY_PATTERN_ENCRYPTED)
                remove(KEY_PATTERN_IV)
            }
            store.plainPrefs?.edit {
                putBoolean(KEY_PATTERN_KEYSTORE_BOUND, false)
                putBoolean(KEY_LOCK_ENABLED, true)
            }
        } catch (e: Exception) {
            throw RuntimeException("PBKDF2WithHmacSHA256 not available on this device", e)
        } finally {
            spec.clearPassword()
            patChars.fill('\u0000')
        }
    }

    /**
     * Hash [pattern] with PBKDF2, then encrypt the hash with the [authenticatedCipher]
     * (which was unlocked via BiometricPrompt). Stores the encrypted hash + IV.
     *
     * @param pattern the raw pattern string
     * @param authenticatedCipher a Cipher obtained from BiometricPrompt's CryptoObject
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setPatternWithCipher(pattern: String?, authenticatedCipher: Cipher) {
        val prefs = store.requireSecurePrefs("setPatternWithCipher")
        if (pattern.isNullOrEmpty()) {
            setPattern(null)
            return
        }
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val patChars = pattern.toCharArray()
        val spec = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val hash = factory.generateSecret(spec).encoded
            // Encrypt the PBKDF2 hash with the KeyStore-backed AES-GCM cipher
            val encrypted = authenticatedCipher.doFinal(hash)
            val iv = authenticatedCipher.iv
            prefs.edit {
                putString(KEY_PATTERN_ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                putString(KEY_PATTERN_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                putString(KEY_PATTERN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                // Keep the plain PBKDF2 hash too: the keystore key is
                // invalidated whenever a new fingerprint is enrolled, and
                // without this hash the pattern could never be verified again.
                putString(KEY_PATTERN_HASH_V2, Base64.encodeToString(hash, Base64.NO_WRAP))
            }
            store.plainPrefs?.edit {
                putBoolean(KEY_PATTERN_KEYSTORE_BOUND, true)
                putBoolean(KEY_LOCK_ENABLED, true)
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to encrypt pattern hash with KeyStore cipher", e)
        } finally {
            spec.clearPassword()
            patChars.fill('\u0000')
        }
    }

    /**
     * Verify [input] against the stored PBKDF2 hash using a timing-safe comparison.
     * Only works for non-KeyStore-bound patterns. For KeyStore-bound patterns,
     * use [verifyPatternWithCipher].
     *
     * Checks lockout state first; on failure, records the attempt.
     *
     * Transparent migration: if the stored hash was created with 100K iterations (V1),
     * the hash is verified against V1 parameters and then re-hashed with 200K iterations.
     *
     * @return true if input matches the stored pattern.
     */
    @JvmStatic
    fun verifyPattern(input: String?): Boolean {
        store.awaitInit()
        if (isLockedOut()) return false
        val prefs = store.prefs ?: return false
        val saltStr = prefs.getString(KEY_PATTERN_SALT, null) ?: return false
        val hashStr = prefs.getString(KEY_PATTERN_HASH_V2, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val expected = Base64.decode(hashStr, Base64.NO_WRAP)
        val patChars = (input ?: "").toCharArray()
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")

            // Try current iteration count first
            val specCurrent = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
            try {
                val actual = factory.generateSecret(specCurrent).encoded
                if (MessageDigest.isEqual(actual, expected)) {
                    resetFailures()
                    return true
                }
            } finally {
                specCurrent.clearPassword()
            }

            // Try legacy iteration count for transparent migration
            val specLegacy = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS_V1, PBKDF2_KEY_BITS)
            try {
                val actual = factory.generateSecret(specLegacy).encoded
                if (MessageDigest.isEqual(actual, expected)) {
                    // Re-hash with current iteration count and save
                    val specMigrate = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
                    try {
                        val newHash = factory.generateSecret(specMigrate).encoded
                        prefs.edit {
                            putString(
                                KEY_PATTERN_HASH_V2,
                                Base64.encodeToString(newHash, Base64.NO_WRAP)
                            )
                        }
                    } finally {
                        specMigrate.clearPassword()
                    }
                    resetFailures()
                    return true
                }
            } finally {
                specLegacy.clearPassword()
            }

            recordFailure()
            return false
        } catch (_: Exception) {
            recordFailure()
            return false
        } finally {
            patChars.fill('\u0000')
        }
    }

    /**
     * Verify [input] against the stored KeyStore-encrypted PBKDF2 hash.
     * The [authenticatedCipher] must have been obtained from BiometricPrompt's CryptoObject.
     *
     * Checks lockout state first; on failure, records the attempt.
     *
     * Transparent migration: if the stored hash was created with 100K iterations (V1),
     * the hash is verified against V1 parameters and then re-hashed with 200K iterations.
     * Note: the re-encrypted ciphertext uses the same AES-GCM cipher, so the KeyStore
     * binding is preserved.
     *
     * @return true if input matches the stored pattern.
     */
    @JvmStatic
    fun verifyPatternWithCipher(input: String?, authenticatedCipher: Cipher): Boolean {
        store.awaitInit()
        if (isLockedOut()) return false
        val prefs = store.prefs ?: return false
        val saltStr = prefs.getString(KEY_PATTERN_SALT, null) ?: return false
        val encryptedStr = prefs.getString(KEY_PATTERN_ENCRYPTED, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val encrypted = Base64.decode(encryptedStr, Base64.NO_WRAP)
        val patChars = (input ?: "").toCharArray()
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            // Decrypt the stored hash using the BiometricPrompt-authenticated cipher
            val expected = authenticatedCipher.doFinal(encrypted)

            // Try current iteration count first
            val specCurrent = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
            try {
                val actual = factory.generateSecret(specCurrent).encoded
                if (MessageDigest.isEqual(actual, expected)) {
                    // Self-heal patterns saved before the plain hash was kept
                    // alongside the encrypted one (see setPatternWithCipher).
                    if (!prefs.contains(KEY_PATTERN_HASH_V2)) {
                        prefs.edit { putString(KEY_PATTERN_HASH_V2, Base64.encodeToString(actual, Base64.NO_WRAP)) }
                    }
                    resetFailures()
                    return true
                }
            } finally {
                specCurrent.clearPassword()
            }

            // Try legacy iteration count for transparent migration
            val specLegacy = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS_V1, PBKDF2_KEY_BITS)
            try {
                val actual = factory.generateSecret(specLegacy).encoded
                if (MessageDigest.isEqual(actual, expected)) {
                    // Re-hash with current iteration count — re-encryption with the same
                    // cipher is not possible (GCM cipher is single-use), so we store the
                    // new PBKDF2 hash directly in the unencrypted field and clear the
                    // encrypted field. The next setPatternWithCipher call (e.g., on
                    // pattern change) will re-encrypt with KeyStore.
                    val specMigrate = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
                    try {
                        val newHash = factory.generateSecret(specMigrate).encoded
                        prefs.edit {
                            putString(
                                KEY_PATTERN_HASH_V2,
                                Base64.encodeToString(newHash, Base64.NO_WRAP)
                            )
                            remove(KEY_PATTERN_ENCRYPTED)
                            remove(KEY_PATTERN_IV)
                        }
                        store.plainPrefs?.edit { putBoolean(KEY_PATTERN_KEYSTORE_BOUND, false) }
                    } finally {
                        specMigrate.clearPassword()
                    }
                    resetFailures()
                    return true
                }
            } finally {
                specLegacy.clearPassword()
            }

            recordFailure()
            return false
        } catch (_: Exception) {
            recordFailure()
            return false
        } finally {
            patChars.fill('\u0000')
        }
    }
}
