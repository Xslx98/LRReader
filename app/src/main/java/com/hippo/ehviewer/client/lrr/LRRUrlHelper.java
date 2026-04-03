package com.hippo.ehviewer.client.lrr;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.lrr.data.LRRServerInfo;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * Shared URL utilities for LANraragi server addresses.
 *
 * Centralises protocol detection, normalisation, and HTTPS→HTTP fallback
 * logic so that every entry-point (ServerConfigScene, ServerListScene)
 * behaves consistently.
 */
public final class LRRUrlHelper {

    private static final String TAG = "LRRUrlHelper";

    private LRRUrlHelper() {} // utility class

    // ─────────────────────────────────────────────
    //  URL normalisation
    // ─────────────────────────────────────────────

    /**
     * Trim whitespace and remove trailing slashes.
     */
    @NonNull
    public static String normalizeUrl(@NonNull String input) {
        String url = input.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * @return true if the input already starts with {@code http://} or {@code https://}.
     */
    public static boolean hasExplicitScheme(@NonNull String input) {
        String lower = input.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    // ─────────────────────────────────────────────
    //  LAN detection
    // ─────────────────────────────────────────────

    /**
     * Check if the URL points to a private / LAN address.
     */
    public static boolean isLanAddress(@NonNull String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;

            if (host.startsWith("192.168.") || host.startsWith("10.")
                    || host.equals("localhost") || host.equals("127.0.0.1")
                    || host.endsWith(".local")) {
                return true;
            }
            // 172.16.0.0 – 172.31.255.255
            if (host.startsWith("172.")) {
                try {
                    int second = Integer.parseInt(host.split("\\.")[1]);
                    return second >= 16 && second <= 31;
                } catch (Exception ignored) {}
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────
    //  HTTPS→HTTP fallback connection
    // ─────────────────────────────────────────────

    /**
     * Callback for asynchronous connect-with-fallback results.
     */
    public interface ConnectCallback {
        /** Connection succeeded on {@code resolvedUrl}. */
        void onSuccess(@NonNull String resolvedUrl, @NonNull LRRServerInfo info,
                       boolean usedHttpFallback);
        /** All attempts failed. */
        void onFailure(@NonNull Exception error);
    }

    /**
     * Build a short-timeout client suitable for connection testing.
     */
    @NonNull
    public static OkHttpClient buildTestClient(@NonNull OkHttpClient baseClient) {
        return baseClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Attempt to connect to a server. If the user did not specify a protocol,
     * try HTTPS first, then fall back to HTTP.
     *
     * <p>This method <b>blocks</b> — call from a background thread.
     *
     * @param testClient   OkHttpClient with short timeouts
     * @param rawInput     user input, already normalised (no trailing slash)
     * @param callback     result callback (called on the <b>calling</b> thread)
     */
    public static void connectWithFallback(@NonNull OkHttpClient testClient,
                                           @NonNull String rawInput,
                                           @NonNull ConnectCallback callback) {
        if (hasExplicitScheme(rawInput)) {
            // User typed scheme explicitly — use as-is
            LRRAuthManager.setServerUrl(rawInput);
            try {
                LRRServerInfo info = doConnect(testClient, rawInput);
                callback.onSuccess(rawInput, info, false);
            } catch (Exception e) {
                callback.onFailure(e);
            }
            return;
        }

        // No explicit scheme → try HTTPS first
        String httpsUrl = "https://" + rawInput;
        String httpUrl  = "http://"  + rawInput;

        LRRAuthManager.setServerUrl(httpsUrl);
        try {
            Log.d(TAG, "Trying HTTPS: " + httpsUrl);
            LRRServerInfo info = doConnect(testClient, httpsUrl);
            callback.onSuccess(httpsUrl, info, false);
            return;
        } catch (Exception e1) {
            Log.d(TAG, "HTTPS failed: " + e1.getMessage());
        }

        // Fallback to HTTP — only permitted for private / LAN addresses.
        // Allowing HTTP on public hosts would expose the Bearer token to
        // a network-layer attacker that forced the HTTPS failure.
        if (!isLanAddress(httpUrl)) {
            LRRAuthManager.setServerUrl(httpsUrl); // restore HTTPS URL
            callback.onFailure(new SecurityException(
                    "HTTPS connection failed and HTTP is not allowed for non-LAN servers. "
                    + "Verify the server address and SSL certificate."));
            return;
        }

        LRRAuthManager.setServerUrl(httpUrl);
        try {
            Log.d(TAG, "Trying HTTP fallback: " + httpUrl);
            LRRServerInfo info = doConnect(testClient, httpUrl);
            callback.onSuccess(httpUrl, info, true);
        } catch (Exception e2) {
            Log.d(TAG, "HTTP fallback also failed: " + e2.getMessage());
            callback.onFailure(e2);
        }
    }

    /**
     * Synchronous connection attempt — must be called off main thread.
     */
    @NonNull
    private static LRRServerInfo doConnect(@NonNull OkHttpClient client,
                                           @NonNull String baseUrl) throws Exception {
        return (LRRServerInfo) LRRCoroutineHelper.runSuspend(
                (scope, cont) -> LRRServerApi.getServerInfo(client, baseUrl, cont)
        );
    }
}
