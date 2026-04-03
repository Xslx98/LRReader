package com.hippo.ehviewer.client.lrr;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

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

    private static SharedPreferences sPrefs;
    private static long sActiveProfileId = 0;
    /** True when KeyStore became unavailable and the user must re-enter credentials. */
    private static boolean sNeedsReauthentication = false;

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
            // KeyStore unavailable (device restore or OS upgrade may corrupt keys).
            // Wipe stored credentials so we don't silently use plaintext values.
            Log.e(TAG, "KeyStore unavailable — wiping stored credentials", e);
            context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().clear().apply();
            sPrefs = context.getApplicationContext()
                    .getSharedPreferences("lrr_auth_fallback", Context.MODE_PRIVATE);
            sNeedsReauthentication = true;
        } catch (IOException e) {
            Log.e(TAG, "I/O error initializing EncryptedSharedPreferences", e);
            sPrefs = context.getApplicationContext()
                    .getSharedPreferences("lrr_auth_fallback", Context.MODE_PRIVATE);
            sNeedsReauthentication = true;
        }
        // Restore active profile across process restarts
        sActiveProfileId = sPrefs.getLong(KEY_ACTIVE_PROFILE_ID, 0L);
    }

    /**
     * @return The configured server base URL, e.g., "http://192.168.1.100:3000"
     */
    @Nullable
    public static String getServerUrl() {
        return sPrefs.getString(KEY_SERVER_URL, null);
    }

    public static void setServerUrl(@NonNull String url) {
        // Remove trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        sPrefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    /**
     * @return The API key (plaintext, not base64-encoded)
     */
    @Nullable
    public static String getApiKey() {
        return sPrefs.getString(KEY_API_KEY, null);
    }

    public static void setApiKey(@Nullable String apiKey) {
        sPrefs.edit().putString(KEY_API_KEY, apiKey).apply();
    }

    /**
     * @return Cached server name from last successful connection
     */
    @Nullable
    public static String getServerName() {
        return sPrefs.getString(KEY_SERVER_NAME, null);
    }

    public static void setServerName(@Nullable String name) {
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
        sPrefs.edit().putLong(KEY_ACTIVE_PROFILE_ID, id).apply();
    }

    /**
     * @return true if encryption was unavailable during initialize() and the user
     *         should be prompted to re-enter their API key.
     */
    public static boolean isNeedsReauthentication() {
        return sNeedsReauthentication;
    }

    /**
     * Clear all stored credentials.
     */
    public static void clear() {
        sPrefs.edit().clear().apply();
        sActiveProfileId = 0;
    }
}
