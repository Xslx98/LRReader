package com.lanraragi.reader.client.api

import android.util.Log
import com.lanraragi.reader.client.api.data.LRRServerInfo
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Shared URL utilities for LANraragi server addresses.
 *
 * Centralises protocol detection, normalisation, and HTTPS->HTTP fallback
 * logic so that every entry-point (ServerConfigScene, ServerListScene)
 * behaves consistently.
 */
object LRRUrlHelper {

    private const val TAG = "LRRUrlHelper"

    // ─────────────────────────────────────────────
    //  URL normalisation
    // ─────────────────────────────────────────────

    /**
     * Trim whitespace and remove trailing slashes.
     */
    @JvmStatic
    fun normalizeUrl(input: String): String {
        var url = input.trim()
        while (url.endsWith("/")) {
            url = url.substring(0, url.length - 1)
        }
        return url
    }

    /**
     * @return true if the input already starts with `http://` or `https://`.
     */
    @JvmStatic
    fun hasExplicitScheme(input: String): Boolean {
        val lower = input.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    // ─────────────────────────────────────────────
    //  LAN detection
    // ─────────────────────────────────────────────

    /**
     * Check if the URL points to a private / LAN address.
     */
    @JvmStatic
    fun isLanAddress(url: String): Boolean {
        return try {
            val uri = URI.create(url)
            val host = uri.host ?: return false

            if (host.startsWith("192.168.") || host.startsWith("10.")
                || host == "localhost" || host == "127.0.0.1"
                || host.endsWith(".local")
            ) {
                return true
            }
            // 172.16.0.0 - 172.31.255.255
            if (host.startsWith("172.")) {
                try {
                    val second = host.split(".")[1].toInt()
                    return second in 16..31
                } catch (_: Exception) {
                    // ignore
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    // ─────────────────────────────────────────────
    //  HTTPS->HTTP fallback connection
    // ─────────────────────────────────────────────

    /**
     * Callback for asynchronous connect-with-fallback results.
     */
    interface ConnectCallback {
        /** Connection succeeded on [resolvedUrl]. */
        fun onSuccess(resolvedUrl: String, info: LRRServerInfo, usedHttpFallback: Boolean)
        /** All attempts failed. */
        fun onFailure(error: Exception)
    }

    /**
     * Build a short-timeout client suitable for connection testing.
     */
    @JvmStatic
    fun buildTestClient(baseClient: OkHttpClient): OkHttpClient {
        return baseClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Attempt to connect to a server. If the user did not specify a protocol,
     * try HTTPS first, then fall back to HTTP.
     *
     * This method **blocks** -- call from a background thread.
     *
     * @param testClient   OkHttpClient with short timeouts
     * @param rawInput     user input, already normalised (no trailing slash)
     * @param callback     result callback (called on the **calling** thread)
     */
    @JvmStatic
    fun connectWithFallback(
        testClient: OkHttpClient,
        rawInput: String,
        callback: ConnectCallback
    ) {
        try {
            if (hasExplicitScheme(rawInput)) {
                LRRAuthManager.setServerUrl(rawInput)
                try {
                    val info = doConnect(testClient, rawInput)
                    callback.onSuccess(rawInput, info, false)
                    return
                } catch (e: Exception) {
                    Log.d(TAG, "Explicit URL failed: ${e.message}")
                    // Explicit http:// — no fallback, report failure directly
                    if (!rawInput.lowercase().startsWith("https://")) {
                        callback.onFailure(e)
                        return
                    }
                    // Explicit https:// failed — try HTTP fallback (LAN only)
                    val httpUrl = "http://" + rawInput.substring("https://".length)
                    if (!isLanAddress(httpUrl)) {
                        callback.onFailure(e)
                        return
                    }
                    LRRAuthManager.setServerUrl(httpUrl)
                    try {
                        Log.d(TAG, "Trying HTTP fallback for explicit HTTPS: $httpUrl")
                        val info = doConnect(testClient, httpUrl)
                        callback.onSuccess(httpUrl, info, true)
                    } catch (e2: Exception) {
                        Log.d(TAG, "HTTP fallback also failed: ${e2.message}")
                        callback.onFailure(e2)
                    }
                }
                return
            }

            // No explicit scheme -> try HTTPS first
            val httpsUrl = "https://$rawInput"
            val httpUrl = "http://$rawInput"

            LRRAuthManager.setServerUrl(httpsUrl)
            try {
                Log.d(TAG, "Trying HTTPS: $httpsUrl")
                val info = doConnect(testClient, httpsUrl)
                callback.onSuccess(httpsUrl, info, false)
                return
            } catch (e1: Exception) {
                Log.d(TAG, "HTTPS failed: ${e1.message}")
            }

            // Fallback to HTTP -- only permitted for private / LAN addresses.
            if (!isLanAddress(httpUrl)) {
                LRRAuthManager.setServerUrl(httpsUrl) // restore HTTPS URL
                callback.onFailure(
                    SecurityException(
                        "HTTPS connection failed and HTTP is not allowed for non-LAN servers. " +
                            "Verify the server address and SSL certificate."
                    )
                )
                return
            }

            LRRAuthManager.setServerUrl(httpUrl)
            try {
                Log.d(TAG, "Trying HTTP fallback: $httpUrl")
                val info = doConnect(testClient, httpUrl)
                callback.onSuccess(httpUrl, info, true)
            } catch (e2: Exception) {
                Log.d(TAG, "HTTP fallback also failed: ${e2.message}")
                callback.onFailure(e2)
            }
        } catch (e: LRRSecureStorageUnavailableException) {
            Log.e(TAG, "Secure storage unavailable during connect", e)
            callback.onFailure(e)
        }
    }

    /**
     * Synchronous connection attempt -- must be called off main thread.
     */
    private fun doConnect(client: OkHttpClient, baseUrl: String): LRRServerInfo {
        return runSuspend { LRRServerApi.getServerInfo(client, baseUrl) }
    }
}
