package com.hippo.ehviewer.client.lrr;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

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

    private static SharedPreferences sPrefs;
    private static long sActiveProfileId = 0;

    public static void initialize(@NonNull Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sPrefs = EncryptedSharedPreferences.create(
                    PREF_NAME,
                    masterKeyAlias,
                    context.getApplicationContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Fallback to regular SharedPreferences if encryption fails
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back", e);
            sPrefs = context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
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
    }

    /**
     * Clear all stored credentials.
     */
    public static void clear() {
        sPrefs.edit().clear().apply();
    }
}
