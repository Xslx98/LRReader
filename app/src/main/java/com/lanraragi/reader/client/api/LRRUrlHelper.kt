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
            // Reject leading-zero octets: a BSD-lineage resolver (inet_aton)
            // reads them as OCTAL, so a decimal parse here would classify a
            // different host than the socket connects to — e.g. "010.0.0.1"
            // parses to 10.x (private) while the resolver dials 8.0.0.1 (WAN).
            if (part.length > 1 && part[0] == '0') return null
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
     * Classify a bracket-stripped IPv6 literal as private/LAN by parsing it to
     * its 16 bytes and checking byte ranges, so compression and casing cannot
     * fool a string-prefix match. Covers loopback (`::1`), unique-local
     * (fc00::/7), link-local (fe80::/10), and an IPv4-mapped tail
     * (`::ffff:a.b.c.d`) whose embedded IPv4 is private. A zone id (`%eth0`)
     * is ignored; anything unparseable returns false.
     */
    @Suppress("ReturnCount")
    private fun isPrivateIpv6(addr: String): Boolean {
        val bytes = parseIpv6Literal(addr.substringBefore('%')) ?: return false
        // IPv4-mapped ::ffff:a.b.c.d — classify the embedded IPv4.
        if ((0..9).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xFF) == 0xFF && (bytes[11].toInt() and 0xFF) == 0xFF
        ) {
            return isPrivateIpv4(
                intArrayOf(
                    bytes[12].toInt() and 0xFF, bytes[13].toInt() and 0xFF,
                    bytes[14].toInt() and 0xFF, bytes[15].toInt() and 0xFF
                )
            )
        }
        // loopback ::1
        if ((0..14).all { bytes[it].toInt() == 0 } && bytes[15].toInt() == 1) return true
        val first = bytes[0].toInt() and 0xFF
        // unique-local fc00::/7 (first byte 0xfc or 0xfd)
        if (first and 0xFE == 0xFC) return true
        // link-local fe80::/10 (first byte 0xfe, next two bits = 10)
        if (first == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80) return true
        return false
    }

    /**
     * Parse a bracket- and zone-stripped IPv6 literal into its 16 bytes, or
     * null if it is not a well-formed literal. Handles a single `::`
     * compression run and an optional trailing IPv4-mapped group.
     */
    @Suppress("ReturnCount")
    private fun parseIpv6Literal(addr: String): ByteArray? {
        if (addr.isEmpty()) return null
        // At most one "::" compression run.
        if (addr.indexOf("::") != addr.lastIndexOf("::")) return null
        val compressAt = addr.indexOf("::")
        val head = if (compressAt < 0) addr else addr.substring(0, compressAt)
        val tail = if (compressAt < 0) "" else addr.substring(compressAt + 2)

        val headBytes = groupsToBytes(if (head.isEmpty()) emptyList() else head.split(":"))
            ?: return null
        val tailBytes = groupsToBytes(if (tail.isEmpty()) emptyList() else tail.split(":"))
            ?: return null

        if (compressAt < 0) {
            return if (headBytes.size == 16) headBytes else null
        }
        val zeros = 16 - headBytes.size - tailBytes.size
        if (zeros < 0) return null
        return ByteArray(16).also { out ->
            var p = 0
            headBytes.forEach { out[p++] = it }
            p += zeros
            tailBytes.forEach { out[p++] = it }
        }
    }

    /** Expand IPv6 hextet groups (optional trailing IPv4 group) to bytes. */
    @Suppress("ReturnCount")
    private fun groupsToBytes(groups: List<String>): ByteArray? {
        val out = ArrayList<Byte>(16)
        for ((idx, g) in groups.withIndex()) {
            if (g.contains(".")) {
                // An IPv4-mapped group is only valid as the final group.
                if (idx != groups.size - 1) return null
                val v4 = parseIpv4Literal(g) ?: return null
                v4.forEach { out.add(it.toByte()) }
            } else {
                if (g.isEmpty() || g.length > 4 || g.any { Character.digit(it, 16) < 0 }) {
                    return null
                }
                val v = g.toInt(16)
                out.add((v ushr 8).toByte())
                out.add((v and 0xFF).toByte())
            }
        }
        return out.toByteArray()
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
     * @param allowCleartext whether the caller has opted into plain HTTP for
     *   this server (the add/edit dialog toggle, or an existing profile's
     *   flag). When true, cleartext HTTP to a non-LAN host is permitted — the
     *   same policy the production [LRRCleartextRejectionInterceptor] applies
     *   to saved profiles. Defaults to false (secure default) so the onboarding
     *   path, which has no opt-in, still refuses WAN cleartext with a key.
     */
    suspend fun connectWithFallback(
        testClient: OkHttpClient,
        rawInput: String,
        apiKey: String?,
        callback: ConnectCallback,
        allowCleartext: Boolean = false
    ) {
        try {
            if (hasExplicitScheme(rawInput)) {
                // Explicit http:// to a non-LAN host would transmit the Bearer
                // key in cleartext over the WAN. Refuse before any request is
                // issued — unless the user has opted into plain HTTP for this
                // server. Same policy the fallback paths below enforce.
                if (isInsecureWanUrl(rawInput) && !allowCleartext) {
                    // Localizable: friendlyError maps the type to
                    // lrr_cleartext_refused_error, whose advice ("enable plain
                    // HTTP for this server, or use HTTPS") is the real escape
                    // hatch. A raw SecurityException would surface this English
                    // message verbatim in all locales.
                    callback.onFailure(
                        LRRCleartextRefusedException(
                            "Cleartext HTTP to a non-LAN host refused: the API " +
                                "key would be sent unencrypted."
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
                    // Explicit https:// failed — try HTTP fallback (LAN or
                    // opted-in only; otherwise report the original HTTPS error).
                    val httpUrl = "http://" + rawInput.substring("https://".length)
                    if (isInsecureWanUrl(httpUrl) && !allowCleartext) {
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

            // Fallback to HTTP -- only for private / LAN addresses, or when the
            // user has opted into plain HTTP for this server.
            if (isInsecureWanUrl(httpUrl) && !allowCleartext) {
                callback.onFailure(
                    LRRCleartextRefusedException(
                        "HTTPS failed and cleartext HTTP to a non-LAN host is refused: " +
                            "the API key would be sent unencrypted."
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
