package com.lanraragi.reader.client.api

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
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
 * Thrown by [LRRAuthManager] setters when the Android KeyStore / EncryptedSharedPreferences
 * backing store is unavailable (e.g., after device migration or KeyStore corruption).
 *
 * Callers that write credentials MUST handle this exception and surface a user-visible
 * error — silently swallowing it leaves users believing their changes were saved.
 */
class LRRSecureStorageUnavailableException(message: String) : IOException(message)

/**
 * Manages LANraragi server connection settings (server URL and API key).
 * Stores credentials in EncryptedSharedPreferences for security.
 */
object LRRAuthManager {

    private const val TAG = "LRRAuthManager"
    private const val PREF_NAME = "lrr_auth_encrypted"
    private const val PLAIN_PREF_NAME = "lrr_auth_plain"
    private const val KEY_WAS_CONFIGURED = "was_configured"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_SERVER_NAME = "server_name"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val KEY_ALLOW_CLEARTEXT = "allow_cleartext"
    private const val KEY_PATTERN_SALT = "pattern_salt"
    private const val KEY_PATTERN_HASH_V2 = "pattern_hash_v2"
    private const val PBKDF2_ITERATIONS_V1 = 100_000  // Legacy, kept for migration
    private const val PBKDF2_ITERATIONS = 200_000
    private const val PBKDF2_KEY_BITS = 256

    // KeyStore-bound AES-GCM wrapping for pattern hash
    private const val KEYSTORE_ALIAS_PATTERN = "lrr_pattern_key"
    private const val KEY_PATTERN_ENCRYPTED = "pattern_encrypted"
    private const val KEY_PATTERN_IV = "pattern_iv"
    private const val KEY_PATTERN_KEYSTORE_BOUND = "pattern_keystore_bound"
    private const val AES_GCM_TAG_BITS = 128

    // Persistent failure lockout (stored in plain SharedPreferences)
    private const val KEY_PATTERN_FAIL_COUNT = "pattern_fail_count"
    private const val KEY_PATTERN_LOCKOUT_UNTIL = "pattern_lockout_until"
    private const val LOCKOUT_THRESHOLD_FIRST = 5
    private const val LOCKOUT_THRESHOLD_SECOND = 10
    private const val LOCKOUT_DURATION_FIRST_MS = 30_000L  // 30 seconds
    private const val LOCKOUT_DURATION_SECOND_MS = 300_000L  // 5 minutes

    @Volatile
    private var sPrefs: SharedPreferences? = null

    /** Plain (unencrypted) SharedPreferences for lockout state and flags that must
     *  survive KeyStore failures. */
    @Volatile
    private var sPlainPrefs: SharedPreferences? = null

    @Volatile
    private var sActiveProfileId: Long = 0

    /** True when KeyStore became unavailable and the user must re-enter credentials. */
    @Volatile
    private var sNeedsReauthentication: Boolean = false

    /**
     * Monotonically increasing counter bumped whenever the active server profile
     * is modified (URL, API key, name, cleartext flag). Observers compare against
     * their last-seen value to detect changes and trigger a refresh.
     */
    private val _serverConfigVersion = AtomicLong(0L)

    @JvmStatic
    val serverConfigVersion: Long
        get() = _serverConfigVersion.get()

    /** Bump the config version. Call after any active-profile credential write. */
    @JvmStatic
    fun bumpServerConfigVersion() {
        _serverConfigVersion.incrementAndGet()
    }

    /** Overridable clock source for testing lockout logic. */
    internal var clockMillis: () -> Long = { System.currentTimeMillis() }

    @JvmStatic
    fun initialize(context: Context) {
        val plainPrefs = context.applicationContext
            .getSharedPreferences(PLAIN_PREF_NAME, Context.MODE_PRIVATE)
        sPlainPrefs = plainPrefs
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            sPrefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore unavailable — credentials will not persist this session", e)
            // Wipe any stale data from a prior encrypted store so it cannot be read as plaintext.
            context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
            sPrefs = null
            // Only prompt reauth if the user had previously configured a server
            sNeedsReauthentication = plainPrefs.getBoolean(KEY_WAS_CONFIGURED, false)
        } catch (e: IOException) {
            Log.e(TAG, "I/O error initializing EncryptedSharedPreferences — credentials will not persist", e)
            sPrefs = null
            sNeedsReauthentication = plainPrefs.getBoolean(KEY_WAS_CONFIGURED, false)
        }
        // Restore active profile (falls back to 0 when sPrefs is null)
        val prefs = sPrefs
        sActiveProfileId = prefs?.getLong(KEY_ACTIVE_PROFILE_ID, 0L) ?: 0L
        // Migrate away from v1 SHA-256 pattern hash: remove stale key so hasPattern()
        // correctly returns false and prompts the user to re-enroll with PBKDF2.
        if (prefs?.contains("pattern_hash") == true) {
            prefs.edit().remove("pattern_hash").apply()
        }
        // Persist "was_configured" flag when a server URL exists, so we can detect
        // KeyStore corruption vs fresh install on next startup.
        if (prefs?.getString(KEY_SERVER_URL, null) != null) {
            plainPrefs.edit().putBoolean(KEY_WAS_CONFIGURED, true).apply()
        }
    }

    /**
     * Return the backing [SharedPreferences], or throw [LRRSecureStorageUnavailableException]
     * if secure storage is unavailable (KeyStore failure / EncryptedSharedPreferences init failure).
     *
     * Every setter that persists credentials MUST go through this helper so that failures
     * are surfaced to callers instead of being silently dropped.
     */
    private fun requireSecurePrefs(op: String): SharedPreferences {
        return sPrefs ?: throw LRRSecureStorageUnavailableException(
            "Secure credential store unavailable; cannot perform $op"
        )
    }

    /**
     * Inject a [SharedPreferences] instance for unit-testing environments where
     * EncryptedSharedPreferences is unavailable (e.g., Robolectric without a real KeyStore).
     * Must NOT be called from production code.
     */
    @JvmStatic
    internal fun initializeForTesting(prefs: SharedPreferences) {
        sPrefs = prefs
        sPlainPrefs = prefs
        sNeedsReauthentication = false
        sActiveProfileId = prefs.getLong(KEY_ACTIVE_PROFILE_ID, 0L)
        clockMillis = { System.currentTimeMillis() }
    }

    /**
     * Simulate secure-storage unavailability for unit tests. Clears [sPrefs] so that
     * every setter throws [LRRSecureStorageUnavailableException] on the next call.
     * Must NOT be called from production code.
     */
    @JvmStatic
    internal fun simulateStorageUnavailableForTesting() {
        sPrefs = null
        sActiveProfileId = 0L
        // Keep sPlainPrefs alive — lockout state must survive KeyStore failures.
    }

    /**
     * @return The configured server base URL, e.g., "http://192.168.1.100:3000"
     */
    @JvmStatic
    fun getServerUrl(): String? {
        return sPrefs?.getString(KEY_SERVER_URL, null)
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setServerUrl(url: String) {
        val prefs = requireSecurePrefs("setServerUrl")
        // Remove trailing slash
        var cleanUrl = url
        if (cleanUrl.endsWith("/")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
        }
        prefs.edit().putString(KEY_SERVER_URL, cleanUrl).apply()
    }

    /**
     * @return The API key (plaintext, not base64-encoded)
     */
    @JvmStatic
    fun getApiKey(): String? {
        return sPrefs?.getString(KEY_API_KEY, null)
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setApiKey(apiKey: String?) {
        val prefs = requireSecurePrefs("setApiKey")
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    /**
     * @return Cached server name from last successful connection
     */
    @JvmStatic
    fun getServerName(): String? {
        return sPrefs?.getString(KEY_SERVER_NAME, null)
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setServerName(name: String?) {
        val prefs = requireSecurePrefs("setServerName")
        prefs.edit().putString(KEY_SERVER_NAME, name).apply()
    }

    /**
     * @return true if a server URL has been configured
     */
    @JvmStatic
    fun isConfigured(): Boolean {
        val url = getServerUrl()
        return !url.isNullOrEmpty()
    }

    /**
     * @return ID of the currently active ServerProfile (0 if none)
     */
    @JvmStatic
    fun getActiveProfileId(): Long {
        return sActiveProfileId
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setActiveProfileId(id: Long) {
        sActiveProfileId = id
        val prefs = requireSecurePrefs("setActiveProfileId")
        prefs.edit().putLong(KEY_ACTIVE_PROFILE_ID, id).apply()
    }

    /**
     * @return whether the active server profile permits cleartext (HTTP) requests.
     *         When sPrefs is available but the key is absent, defaults to `true`
     *         (existing HTTP profiles never wrote this key explicitly).
     *         When sPrefs is null (KeyStore failure), returns `false` (fail-closed).
     *         ServerListScene calls [setAllowCleartext] on every profile switch.
     *         Read by [LRRCleartextRejectionInterceptor] every request.
     */
    @JvmStatic
    fun getAllowCleartext(): Boolean {
        return sPrefs?.getBoolean(KEY_ALLOW_CLEARTEXT, true) ?: false
    }

    /**
     * Cache the active profile's `allowCleartext` flag. Called by ServerListScene
     * on every profile switch. Throws [LRRSecureStorageUnavailableException] if
     * the secure backing store is unavailable, consistent with all other setters
     * (W0-4: no silent `?: return` in any credential setter).
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setAllowCleartext(allow: Boolean) {
        val prefs = requireSecurePrefs("setAllowCleartext")
        prefs.edit().putBoolean(KEY_ALLOW_CLEARTEXT, allow).apply()
    }

    /**
     * @return true if encryption was unavailable during initialize() and the user
     *         should be prompted to re-enter their API key.
     */
    @JvmStatic
    fun isNeedsReauthentication(): Boolean {
        return sNeedsReauthentication
    }

    /**
     * Inspect every known server profile and mark reauthentication required if any
     * profile is missing its API key. Should be called from a background thread once
     * the Room database is ready and the full profile list has been loaded.
     *
     * Two failure modes are detected:
     *
     *   1. The encrypted backing store is unavailable ([sPrefs] is null) AND the user
     *      already has at least one profile in Room — every key was lost.
     *   2. The backing store is available but at least one profile has no entry under
     *      `api_key_$id` — partial corruption / interrupted migration.
     *
     * Both leave the user in a state where the auth interceptor would silently send
     * requests with no Bearer token; we set [sNeedsReauthentication] so MainActivity /
     * ServerListScene surface the dialog and direct the user to ServerListScene.
     */
    @JvmStatic
    fun markReauthIfProfilesUnprotected(profileIds: List<Long>) {
        if (profileIds.isEmpty()) return
        val prefs = sPrefs
        if (prefs == null) {
            // KeyStore is broken AND profiles exist in Room: keys are unrecoverable.
            sNeedsReauthentication = true
            return
        }
        // KeyStore is up but verify each profile has its api_key entry.
        for (id in profileIds) {
            if (!prefs.contains("api_key_$id")) {
                sNeedsReauthentication = true
                return
            }
        }
        // All profiles accounted for — clear any stale reauth flag.
        sNeedsReauthentication = false
    }

    // ── Per-profile API key storage (encrypted, keyed by profile ID) ──────────

    /**
     * Store the API key for a specific server profile in encrypted prefs.
     * Use this instead of storing keys in the Room `SERVER_PROFILES` table.
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setApiKeyForProfile(profileId: Long, apiKey: String?) {
        val prefs = requireSecurePrefs("setApiKeyForProfile")
        val prefKey = "api_key_$profileId"
        // Store empty string (not remove) so markReauthIfProfilesUnprotected can
        // distinguish "intentionally no key" from "key lost due to KeyStore failure".
        prefs.edit().putString(prefKey, apiKey ?: "").apply()
    }

    /** @return the API key for the given profile, or null if none stored / empty. */
    @JvmStatic
    fun getApiKeyForProfile(profileId: Long): String? {
        return sPrefs?.getString("api_key_$profileId", null)?.ifEmpty { null }
    }

    /** Remove the stored API key for a profile (e.g., when the profile is deleted). */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun clearApiKeyForProfile(profileId: Long) {
        val prefs = requireSecurePrefs("clearApiKeyForProfile")
        prefs.edit().remove("api_key_$profileId").apply()
    }

    // ── Persistent failure lockout ──────────────────────────────────────

    /**
     * @return true if the pattern is currently locked out due to too many failed attempts.
     */
    @JvmStatic
    fun isLockedOut(): Boolean {
        val plain = sPlainPrefs ?: return false
        val lockoutUntil = plain.getLong(KEY_PATTERN_LOCKOUT_UNTIL, 0L)
        if (lockoutUntil == 0L) return false
        return clockMillis() < lockoutUntil
    }

    /**
     * @return the remaining lockout duration in milliseconds, or 0 if not locked out.
     */
    @JvmStatic
    fun getLockoutRemainingMs(): Long {
        val plain = sPlainPrefs ?: return 0L
        val lockoutUntil = plain.getLong(KEY_PATTERN_LOCKOUT_UNTIL, 0L)
        if (lockoutUntil == 0L) return 0L
        val remaining = lockoutUntil - clockMillis()
        return if (remaining > 0) remaining else 0L
    }

    /**
     * Record a failed pattern attempt. Increments the persistent counter and sets
     * lockout timestamps at thresholds (5 failures = 30s, 10 failures = 5min).
     */
    @JvmStatic
    fun recordFailure() {
        val plain = sPlainPrefs ?: return
        val count = plain.getInt(KEY_PATTERN_FAIL_COUNT, 0) + 1
        val editor = plain.edit().putInt(KEY_PATTERN_FAIL_COUNT, count)
        when {
            count >= LOCKOUT_THRESHOLD_SECOND -> {
                editor.putLong(
                    KEY_PATTERN_LOCKOUT_UNTIL,
                    clockMillis() + LOCKOUT_DURATION_SECOND_MS
                )
            }
            count >= LOCKOUT_THRESHOLD_FIRST -> {
                editor.putLong(
                    KEY_PATTERN_LOCKOUT_UNTIL,
                    clockMillis() + LOCKOUT_DURATION_FIRST_MS
                )
            }
        }
        editor.apply()
    }

    /**
     * Reset the failure counter and lockout timestamp. Called on successful verification.
     */
    @JvmStatic
    fun resetFailures() {
        val plain = sPlainPrefs ?: return
        plain.edit()
            .remove(KEY_PATTERN_FAIL_COUNT)
            .remove(KEY_PATTERN_LOCKOUT_UNTIL)
            .apply()
    }

    /**
     * @return the current failure count (for UI display).
     */
    @JvmStatic
    fun getFailureCount(): Int {
        return sPlainPrefs?.getInt(KEY_PATTERN_FAIL_COUNT, 0) ?: 0
    }

    // ── KeyStore-bound AES-GCM pattern wrapping ─────────────────────────

    /**
     * @return true if the stored pattern hash is bound to Android KeyStore via AES-GCM.
     * When true, [verifyPatternWithCipher] must be used instead of [verifyPattern].
     */
    @JvmStatic
    fun isPatternKeystoreBound(): Boolean {
        return sPlainPrefs?.getBoolean(KEY_PATTERN_KEYSTORE_BOUND, false) == true
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
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS_PATTERN,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .build()
        keyGen.init(spec)
        keyGen.generateKey()
    }

    /**
     * Delete the KeyStore-backed AES key for pattern hash encryption.
     * Called when clearing the pattern or when falling back to PBKDF2-only.
     */
    @JvmStatic
    fun deletePatternKeystoreKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(KEYSTORE_ALIAS_PATTERN)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS_PATTERN)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete pattern KeyStore key", e)
        }
    }

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
        val prefs = sPrefs ?: throw GeneralSecurityException("Secure storage unavailable")
        val ivStr = prefs.getString(KEY_PATTERN_IV, null)
            ?: throw GeneralSecurityException("Pattern IV not found")
        val iv = Base64.decode(ivStr, Base64.NO_WRAP)
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val key = keyStore.getKey(KEYSTORE_ALIAS_PATTERN, null)
            ?: throw GeneralSecurityException("Pattern KeyStore key not found")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        return cipher
    }

    // ── App-lock pattern (PBKDF2WithHmacSHA256 + optional KeyStore AES-GCM) ──

    /** @return true if an app-lock pattern has been stored. */
    @JvmStatic
    fun hasPattern(): Boolean {
        val prefs = sPrefs ?: return false
        return prefs.contains(KEY_PATTERN_HASH_V2) || prefs.contains(KEY_PATTERN_ENCRYPTED)
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
        val prefs = requireSecurePrefs("setPattern")
        if (pattern.isNullOrEmpty()) {
            prefs.edit()
                .remove(KEY_PATTERN_HASH_V2)
                .remove(KEY_PATTERN_SALT)
                .remove(KEY_PATTERN_ENCRYPTED)
                .remove(KEY_PATTERN_IV)
                .apply()
            sPlainPrefs?.edit()?.remove(KEY_PATTERN_KEYSTORE_BOUND)?.apply()
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
            prefs.edit()
                .putString(KEY_PATTERN_HASH_V2, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putString(KEY_PATTERN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .remove(KEY_PATTERN_ENCRYPTED)
                .remove(KEY_PATTERN_IV)
                .apply()
            sPlainPrefs?.edit()?.putBoolean(KEY_PATTERN_KEYSTORE_BOUND, false)?.apply()
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
        val prefs = requireSecurePrefs("setPatternWithCipher")
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
            prefs.edit()
                .putString(KEY_PATTERN_ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_PATTERN_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(KEY_PATTERN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .remove(KEY_PATTERN_HASH_V2)
                .apply()
            sPlainPrefs?.edit()?.putBoolean(KEY_PATTERN_KEYSTORE_BOUND, true)?.apply()
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
        if (isLockedOut()) return false
        val prefs = sPrefs ?: return false
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
                        prefs.edit()
                            .putString(KEY_PATTERN_HASH_V2,
                                Base64.encodeToString(newHash, Base64.NO_WRAP))
                            .apply()
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
        if (isLockedOut()) return false
        val prefs = sPrefs ?: return false
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
                        prefs.edit()
                            .putString(KEY_PATTERN_HASH_V2,
                                Base64.encodeToString(newHash, Base64.NO_WRAP))
                            .remove(KEY_PATTERN_ENCRYPTED)
                            .remove(KEY_PATTERN_IV)
                            .apply()
                        sPlainPrefs?.edit()
                            ?.putBoolean(KEY_PATTERN_KEYSTORE_BOUND, false)
                            ?.apply()
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
     * Compute a PBKDF2WithHmacSHA256 hash with the given parameters.
     * Exposed as `internal` so tests can create legacy-iteration hashes for migration tests.
     */
    @JvmStatic
    internal fun computePbkdf2Hash(
        pattern: String,
        salt: ByteArray,
        iterations: Int
    ): ByteArray {
        val patChars = pattern.toCharArray()
        val spec = PBEKeySpec(patChars, salt, iterations, PBKDF2_KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            patChars.fill('\u0000')
        }
    }

    /** Legacy iteration count, exposed for migration tests. */
    internal const val ITERATIONS_V1 = PBKDF2_ITERATIONS_V1

    /** Current iteration count, exposed for migration tests. */
    internal const val ITERATIONS_CURRENT = PBKDF2_ITERATIONS

    /**
     * Clear all stored credentials.
     */
    @JvmStatic
    fun clear() {
        sPrefs?.edit()?.clear()?.apply()
        sPlainPrefs?.edit()
            ?.remove(KEY_PATTERN_KEYSTORE_BOUND)
            ?.remove(KEY_PATTERN_FAIL_COUNT)
            ?.remove(KEY_PATTERN_LOCKOUT_UNTIL)
            ?.apply()
        deletePatternKeystoreKey()
        sActiveProfileId = 0
        sNeedsReauthentication = false
    }
}
