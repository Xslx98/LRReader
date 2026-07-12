package com.lanraragi.reader.client.api

import android.util.Log
import com.lanraragi.reader.client.api.data.LRRServerInfo
import kotlinx.coroutines.CancellationException
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
     *
     * A host counts as LAN only when it is `localhost`, an mDNS `*.local`
     * name (resolvable on the local link only), or a **numeric IP literal**
     * inside a private range. A hostname that merely *starts with* a private
     * prefix (e.g. `192.168.evil.com`) is a public DNS name and is NOT LAN —
     * treating it as LAN would let the cleartext gate leak the API key to an
     * attacker-controlled host.
     */
    @JvmStatic
    fun isLanAddress(url: String): Boolean {
        return try {
            val host = URI.create(url).host?.lowercase() ?: return false

            // mDNS names resolve on the local link only.
            if (host == "localhost" || host.endsWith(".local")) {
                return true
            }
            // URI.getHost() wraps IPv6 literals in brackets.
            if (host.startsWith("[") && host.endsWith("]")) {
                return isPrivateIpv6(host.substring(1, host.length - 1))
            }
            val octets = parseIpv4Literal(host) ?: return false
            isPrivateIpv4(octets)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parse [host] as a strict dotted-quad IPv4 literal (four ASCII-digit
     * octets in 0..255). Returns null for anything else — including hostnames
     * that happen to start with digits.
     */
    private fun parseIpv4Literal(host: String): IntArray? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) {
                return null
            }
            val value = part.toInt()
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isPrivateIpv4(o: IntArray): Boolean = when {
        o[0] == 10 -> true                          // 10.0.0.0/8
        o[0] == 127 -> true                         // loopback 127.0.0.0/8
        o[0] == 192 && o[1] == 168 -> true          // 192.168.0.0/16
        o[0] == 172 && o[1] in 16..31 -> true       // 172.16.0.0/12
        o[0] == 169 && o[1] == 254 -> true          // link-local 169.254.0.0/16
        o[0] == 100 && o[1] in 64..127 -> true      // CGNAT / Tailscale 100.64.0.0/10
        else -> false
    }

    /**
     * Classify a bracket-stripped IPv6 literal. Covers loopback, unique-local
     * (fc00::/7) and link-local (fe80::/10); a zone id (`%eth0`) is ignored.
     */
    private fun isPrivateIpv6(addr: String): Boolean {
        val a = addr.substringBefore('%')
        return when {
            a == "::1" -> true                                  // loopback
            a.startsWith("fc") || a.startsWith("fd") -> true     // unique-local fc00::/7
            a.startsWith("fe8") || a.startsWith("fe9") ||
                a.startsWith("fea") || a.startsWith("feb") -> true // link-local fe80::/10
            else -> false
        }
    }

    /**
     * True when [url] is a plain-HTTP address to a host that is not a
     * private/LAN IP — i.e. sending the API key to it would put the key on the
     * cleartext WAN. The per-profile `allowCleartext` opt-in (or HTTPS) is the
     * intended escape hatch. Single source of truth for the cleartext-WAN
     * predicate shared by [connectWithFallback]'s gate, ServerConfig's
     * post-success warning, and ServerListScene's switch/add warnings.
     */
    @JvmStatic
    fun isInsecureWanUrl(url: String): Boolean =
        url.lowercase().startsWith("http://") && !isLanAddress(url)

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
     *
     * NET-7: strips [LRRAuthInterceptor] and [LRRCleartextRejectionInterceptor]
     * — both consult process-global / profile state that a *candidate* server
     * must not depend on. Auth rides the probe request itself
     * ([LRRServerApi.getServerInfo]'s apiKey variant); cleartext policy for
     * candidates is [connectWithFallback]'s isLanAddress gate (WAN HTTP is
     * refused before any request is issued).
     */
    @JvmStatic
    fun buildTestClient(baseClient: OkHttpClient): OkHttpClient {
        return baseClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .apply {
                networkInterceptors().removeAll {
                    it is LRRAuthInterceptor || it is LRRCleartextRejectionInterceptor
                }
            }
            .build()
    }

    /**
     * Attempt to connect to a server. If the user did not specify a protocol,
     * try HTTPS first, then fall back to HTTP.
     *
     * @param testClient   OkHttpClient with short timeouts
     * @param rawInput     user input, already normalised (no trailing slash)
     * @param apiKey       candidate server's API key, attached per-request
     *   (null/empty for open servers)
     * @param callback     result callback (invoked in the calling coroutine
     *   before this function returns)
     */
    suspend fun connectWithFallback(
        testClient: OkHttpClient,
        rawInput: String,
        apiKey: String?,
        callback: ConnectCallback
    ) {
        try {
            if (hasExplicitScheme(rawInput)) {
                // Explicit http:// to a non-LAN host would transmit the Bearer
                // key in cleartext over the WAN. Refuse before any request is
                // issued — the same policy the schemeless and https->http
                // fallback paths already enforce below.
                if (isInsecureWanUrl(rawInput)) {
                    callback.onFailure(
                        SecurityException(
                            "HTTP is not allowed for non-LAN servers; the API key " +
                                "would be sent in cleartext. Use HTTPS."
                        )
                    )
                    return
                }
                try {
                    val info = LRRServerApi.getServerInfo(testClient, rawInput, apiKey)
                    callback.onSuccess(rawInput, info, false)
                    return
                } catch (ce: CancellationException) {
                    throw ce
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
                    try {
                        Log.d(TAG, "Trying HTTP fallback for explicit HTTPS: $httpUrl")
                        val info = LRRServerApi.getServerInfo(testClient, httpUrl, apiKey)
                        callback.onSuccess(httpUrl, info, true)
                    } catch (ce: CancellationException) {
                        throw ce
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

            try {
                Log.d(TAG, "Trying HTTPS: $httpsUrl")
                val info = LRRServerApi.getServerInfo(testClient, httpsUrl, apiKey)
                callback.onSuccess(httpsUrl, info, false)
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (e1: Exception) {
                Log.d(TAG, "HTTPS failed: ${e1.message}")
            }

            // Fallback to HTTP -- only permitted for private / LAN addresses.
            if (!isLanAddress(httpUrl)) {
                callback.onFailure(
                    SecurityException(
                        "HTTPS connection failed and HTTP is not allowed for non-LAN servers. " +
                            "Verify the server address and SSL certificate."
                    )
                )
                return
            }

            try {
                Log.d(TAG, "Trying HTTP fallback: $httpUrl")
                val info = LRRServerApi.getServerInfo(testClient, httpUrl, apiKey)
                callback.onSuccess(httpUrl, info, true)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e2: Exception) {
                Log.d(TAG, "HTTP fallback also failed: ${e2.message}")
                callback.onFailure(e2)
            }
        } catch (e: LRRSecureStorageUnavailableException) {
            Log.e(TAG, "Secure storage unavailable during connect", e)
            callback.onFailure(e)
        }
    }
}
