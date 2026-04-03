package com.hippo.ehviewer.client.lrr;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Manages LANraragi server connection settings (server URL and API key).
 * Stores credentials in EncryptedSharedPreferences for security.
 */
public class LRRAuthManager {

    private static final String TAG = "LRRAuthManager";
    private static final String PREF_NAME = "lrr_auth_encrypted";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_SERVER_NAME = "server_name";
    private static final String KEY_ACTIVE_PROFILE_ID = "active_profile_id";
    private static final String KEY_PATTERN_SALT = "pattern_salt";
    private static final String KEY_PATTERN_HASH_V2 = "pattern_hash_v2";
    private static final int    PBKDF2_ITERATIONS   = 100_000;
    private static final int    PBKDF2_KEY_BITS     = 256;

    private static volatile SharedPreferences sPrefs;
    private static volatile long sActiveProfileId = 0;
    /** True when KeyStore became unavailable and the user must re-enter credentials. */
    private static volatile boolean sNeedsReauthentication = false;

    public static void initialize(@NonNull Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            sPrefs = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "KeyStore unavailable — credentials will not persist this session", e);
            // Wipe any stale data from a prior encrypted store so it cannot be read as plaintext.
            context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().clear().apply();
            sPrefs = null;
            sNeedsReauthentication = true;
        } catch (IOException e) {
            Log.e(TAG, "I/O error initializing EncryptedSharedPreferences — credentials will not persist", e);
            sPrefs = null;
            sNeedsReauthentication = true;
        }
        // Restore active profile (falls back to 0 when sPrefs is null)
        sActiveProfileId = sPrefs != null ? sPrefs.getLong(KEY_ACTIVE_PROFILE_ID, 0L) : 0L;
        // Migrate away from v1 SHA-256 pattern hash: remove stale key so hasPattern()
        // correctly returns false and prompts the user to re-enroll with PBKDF2.
        if (sPrefs != null && sPrefs.contains("pattern_hash")) {
            sPrefs.edit().remove("pattern_hash").apply();
        }
    }

    /**
     * Inject a {@link SharedPreferences} instance for unit-testing environments where
     * EncryptedSharedPreferences is unavailable (e.g., Robolectric without a real KeyStore).
     * Must NOT be called from production code.
     */
    static void initializeForTesting(@NonNull SharedPreferences prefs) {
        sPrefs = prefs;
        sNeedsReauthentication = false;
        sActiveProfileId = prefs.getLong(KEY_ACTIVE_PROFILE_ID, 0L);
    }

    /**
     * @return The configured server base URL, e.g., "http://192.168.1.100:3000"
     */
    @Nullable
    public static String getServerUrl() {
        return sPrefs != null ? sPrefs.getString(KEY_SERVER_URL, null) : null;
    }

    public static void setServerUrl(@NonNull String url) {
        if (sPrefs == null) return;
        // Remove trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        sPrefs.edit().putString(KEY_SERVER_URL, url).commit();
    }

    /**
     * @return The API key (plaintext, not base64-encoded)
     */
    @Nullable
    public static String getApiKey() {
        return sPrefs != null ? sPrefs.getString(KEY_API_KEY, null) : null;
    }

    public static void setApiKey(@Nullable String apiKey) {
        if (sPrefs == null) return;
        sPrefs.edit().putString(KEY_API_KEY, apiKey).commit();
    }

    /**
     * @return Cached server name from last successful connection
     */
    @Nullable
    public static String getServerName() {
        return sPrefs != null ? sPrefs.getString(KEY_SERVER_NAME, null) : null;
    }

    public static void setServerName(@Nullable String name) {
        if (sPrefs == null) return;
        sPrefs.edit().putString(KEY_SERVER_NAME, name).apply();
    }

    /**
     * @return true if a server URL has been configured
     */
    public static boolean isConfigured() {
        String url = getServerUrl();
        return url != null && !url.isEmpty();
    }

    /**
     * @return ID of the currently active ServerProfile (0 if none)
     */
    public static long getActiveProfileId() {
        return sActiveProfileId;
    }

    public static void setActiveProfileId(long id) {
        sActiveProfileId = id;
        if (sPrefs != null) sPrefs.edit().putLong(KEY_ACTIVE_PROFILE_ID, id).commit();
    }

    /**
     * @return true if encryption was unavailable during initialize() and the user
     *         should be prompted to re-enter their API key.
     * TODO: Surface this flag in the server setup/login UI to prompt re-entry.
     */
    public static boolean isNeedsReauthentication() {
        return sNeedsReauthentication;
    }

    // ── Per-profile API key storage (encrypted, keyed by profile ID) ──────────

    /**
     * Store the API key for a specific server profile in encrypted prefs.
     * Use this instead of storing keys in the Room {@code SERVER_PROFILES} table.
     */
    public static void setApiKeyForProfile(long profileId, @Nullable String apiKey) {
        if (sPrefs == null) return;
        String prefKey = "api_key_" + profileId;
        if (apiKey == null || apiKey.isEmpty()) {
            sPrefs.edit().remove(prefKey).commit();
        } else {
            sPrefs.edit().putString(prefKey, apiKey).commit();
        }
    }

    /** @return the API key for the given profile, or null if none stored. */
    @Nullable
    public static String getApiKeyForProfile(long profileId) {
        return sPrefs != null ? sPrefs.getString("api_key_" + profileId, null) : null;
    }

    /** Remove the stored API key for a profile (e.g., when the profile is deleted). */
    public static void clearApiKeyForProfile(long profileId) {
        if (sPrefs == null) return;
        sPrefs.edit().remove("api_key_" + profileId).apply();
    }

    // ── App-lock pattern (PBKDF2WithHmacSHA256 + random salt, never plaintext) ──

    /** @return true if an app-lock pattern has been stored. */
    public static boolean hasPattern() {
        return sPrefs != null && sPrefs.contains(KEY_PATTERN_HASH_V2);
    }

    /**
     * Hash {@code pattern} with PBKDF2WithHmacSHA256 (100K iterations) and persist to encrypted prefs.
     * Pass null or empty string to clear the pattern.
     */
    public static void setPattern(@Nullable String pattern) {
        if (sPrefs == null) return;
        if (pattern == null || pattern.isEmpty()) {
            sPrefs.edit()
                    .remove(KEY_PATTERN_HASH_V2)
                    .remove(KEY_PATTERN_SALT)
                    .apply();
            return;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        char[] patChars = pattern.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            sPrefs.edit()
                    .putString(KEY_PATTERN_HASH_V2, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .putString(KEY_PATTERN_SALT,    Base64.encodeToString(salt, Base64.NO_WRAP))
                    .apply();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("PBKDF2WithHmacSHA256 not available on this device", e);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(patChars, '\0');
        }
    }

    /**
     * Verify {@code input} against the stored PBKDF2 hash using a timing-safe comparison.
     *
     * @return true if input matches the stored pattern.
     */
    public static boolean verifyPattern(@Nullable String input) {
        if (sPrefs == null) return false;
        String saltStr = sPrefs.getString(KEY_PATTERN_SALT, null);
        String hashStr = sPrefs.getString(KEY_PATTERN_HASH_V2, null);
        if (saltStr == null || hashStr == null) return false;
        byte[] salt     = Base64.decode(saltStr, Base64.NO_WRAP);
        byte[] expected = Base64.decode(hashStr, Base64.NO_WRAP);
        char[] patChars = (input != null ? input : "").toCharArray();
        PBEKeySpec spec = new PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] actual = factory.generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(actual, expected);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            return false;
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(patChars, '\0');
        }
    }

    /**
     * Clear all stored credentials.
     */
    public static void clear() {
        if (sPrefs != null) sPrefs.edit().clear().apply();
        sActiveProfileId = 0;
        sNeedsReauthentication = false;
    }
}
