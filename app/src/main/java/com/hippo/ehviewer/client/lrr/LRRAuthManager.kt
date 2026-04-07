package com.hippo.ehviewer.client.lrr

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
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
    private const val KEY_PATTERN_SALT = "pattern_salt"
    private const val KEY_PATTERN_HASH_V2 = "pattern_hash_v2"
    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_KEY_BITS = 256

    @Volatile
    private var sPrefs: SharedPreferences? = null

    @Volatile
    private var sActiveProfileId: Long = 0

    /** True when KeyStore became unavailable and the user must re-enter credentials. */
    @Volatile
    private var sNeedsReauthentication: Boolean = false

    @JvmStatic
    fun initialize(context: Context) {
        val plainPrefs = context.applicationContext
            .getSharedPreferences(PLAIN_PREF_NAME, Context.MODE_PRIVATE)
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
        sNeedsReauthentication = false
        sActiveProfileId = prefs.getLong(KEY_ACTIVE_PROFILE_ID, 0L)
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
        if (apiKey.isNullOrEmpty()) {
            prefs.edit().remove(prefKey).apply()
        } else {
            prefs.edit().putString(prefKey, apiKey).apply()
        }
    }

    /** @return the API key for the given profile, or null if none stored. */
    @JvmStatic
    fun getApiKeyForProfile(profileId: Long): String? {
        return sPrefs?.getString("api_key_$profileId", null)
    }

    /** Remove the stored API key for a profile (e.g., when the profile is deleted). */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun clearApiKeyForProfile(profileId: Long) {
        val prefs = requireSecurePrefs("clearApiKeyForProfile")
        prefs.edit().remove("api_key_$profileId").apply()
    }

    // ── App-lock pattern (PBKDF2WithHmacSHA256 + random salt, never plaintext) ──

    /** @return true if an app-lock pattern has been stored. */
    @JvmStatic
    fun hasPattern(): Boolean {
        return sPrefs?.contains(KEY_PATTERN_HASH_V2) == true
    }

    /**
     * Hash [pattern] with PBKDF2WithHmacSHA256 (100K iterations) and persist to encrypted prefs.
     * Pass null or empty string to clear the pattern.
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setPattern(pattern: String?) {
        val prefs = requireSecurePrefs("setPattern")
        if (pattern.isNullOrEmpty()) {
            prefs.edit()
                .remove(KEY_PATTERN_HASH_V2)
                .remove(KEY_PATTERN_SALT)
                .apply()
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
                .apply()
        } catch (e: Exception) {
            throw RuntimeException("PBKDF2WithHmacSHA256 not available on this device", e)
        } finally {
            spec.clearPassword()
            patChars.fill('\u0000')
        }
    }

    /**
     * Verify [input] against the stored PBKDF2 hash using a timing-safe comparison.
     *
     * @return true if input matches the stored pattern.
     */
    @JvmStatic
    fun verifyPattern(input: String?): Boolean {
        val prefs = sPrefs ?: return false
        val saltStr = prefs.getString(KEY_PATTERN_SALT, null) ?: return false
        val hashStr = prefs.getString(KEY_PATTERN_HASH_V2, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val expected = Base64.decode(hashStr, Base64.NO_WRAP)
        val patChars = (input ?: "").toCharArray()
        val spec = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val actual = factory.generateSecret(spec).encoded
            return MessageDigest.isEqual(actual, expected)
        } catch (_: Exception) {
            return false
        } finally {
            spec.clearPassword()
            patChars.fill('\u0000')
        }
    }

    /**
     * Clear all stored credentials.
     */
    @JvmStatic
    fun clear() {
        sPrefs?.edit()?.clear()?.apply()
        sActiveProfileId = 0
        sNeedsReauthentication = false
    }
}
