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
     * @return The configured server base URL, e.g., "http://192.168.1.100:3000"
     */
    @JvmStatic
    fun getServerUrl(): String? {
        return sPrefs?.getString(KEY_SERVER_URL, null)
    }

    @JvmStatic
    fun setServerUrl(url: String) {
        val prefs = sPrefs ?: return
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
    fun setApiKey(apiKey: String?) {
        val prefs = sPrefs ?: return
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
    fun setServerName(name: String?) {
        val prefs = sPrefs ?: return
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
    fun setActiveProfileId(id: Long) {
        sActiveProfileId = id
        val prefs = sPrefs ?: return
        prefs.edit().putLong(KEY_ACTIVE_PROFILE_ID, id).apply()
    }

    /**
     * @return true if encryption was unavailable during initialize() and the user
     *         should be prompted to re-enter their API key.
     * TODO: Surface this flag in the server setup/login UI to prompt re-entry.
     */
    @JvmStatic
    fun isNeedsReauthentication(): Boolean {
        return sNeedsReauthentication
    }

    // ── Per-profile API key storage (encrypted, keyed by profile ID) ──────────

    /**
     * Store the API key for a specific server profile in encrypted prefs.
     * Use this instead of storing keys in the Room `SERVER_PROFILES` table.
     */
    @JvmStatic
    fun setApiKeyForProfile(profileId: Long, apiKey: String?) {
        val prefs = sPrefs ?: return
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
    fun clearApiKeyForProfile(profileId: Long) {
        val prefs = sPrefs ?: return
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
    fun setPattern(pattern: String?) {
        val prefs = sPrefs ?: return
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
