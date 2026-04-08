package com.hippo.ehviewer.client.lrr

import android.util.Base64
import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that injects the LANraragi Bearer token ONLY for requests
 * targeting the configured LANraragi server. This prevents leaking the API key
 * to third-party hosts (e.g., external image CDNs or link previews).
 *
 * Token format: Bearer Base64(api_key)
 *
 * Hardening notes (W0-2):
 *  - Match logic is **pure string equality** on host + port + scheme. No DNS
 *    resolution happens here, so DNS rebinding cannot bypass the host check
 *    and OkHttp worker threads are never blocked on synchronous lookups.
 *  - Scheme participates in matching: an HTTPS-configured server with an HTTP
 *    request (or vice versa) is treated as a credential-downgrade attempt and
 *    aborted with [LRRPlaintextRefusedException] before the token is sent.
 *  - URLs containing userInfo (`user:pass@`) or a fragment (`#`) are rejected
 *    outright, since both would be a sign of a malformed/malicious server URL.
 */
class LRRAuthInterceptor : Interceptor {

    companion object {
        private const val TAG = "LRRAuthInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val apiKey = LRRAuthManager.getApiKey()
        val original = chain.request()

        if (apiKey.isNullOrEmpty()) {
            return chain.proceed(original)
        }

        val serverUrl = LRRAuthManager.getServerUrl()
        if (serverUrl.isNullOrEmpty()) {
            // Surface at error level so the line survives R8 log stripping in release.
            // No host is included: the intent is only to flag the missing-config state,
            // not to leak which host the caller was trying to reach.
            Log.e(TAG, "LRR auth interceptor: request without base URL configured")
            return chain.proceed(original)
        }

        when (matchesConfiguredServer(original.url, serverUrl)) {
            ServerMatchResult.MATCH -> {
                val token = Base64.encodeToString(apiKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val authed = original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
                return chain.proceed(authed)
            }
            ServerMatchResult.SCHEME_DOWNGRADE -> {
                // Host:port matches but scheme differs — credential downgrade attempt.
                // Abort the request before the API key leaves the device.
                Log.e(TAG, "Refusing to send API key: scheme mismatch with configured server")
                throw LRRPlaintextRefusedException(
                    "Scheme mismatch between request and configured server"
                )
            }
            ServerMatchResult.MISMATCH -> {
                // Different host or port — not our server. Pass through without injecting.
                return chain.proceed(original)
            }
        }
    }
}

/**
 * Result of comparing a request URL against the configured LANraragi server URL.
 *
 *  - [MATCH]            Same host, same port, same scheme — inject the bearer token.
 *  - [SCHEME_DOWNGRADE] Same host and port but different scheme — abort the request.
 *  - [MISMATCH]         Different host or port — pass through without injecting.
 */
internal enum class ServerMatchResult { MATCH, SCHEME_DOWNGRADE, MISMATCH }

/**
 * Decide whether [requestUrl] targets the same endpoint as [serverUrlString], using
 * **pure string equality** on host + port + scheme. No DNS resolution and no I/O.
 *
 * Both sides are parsed via [HttpUrl] which strips IPv6 brackets, normalises host
 * to lowercase, and resolves the default port for the scheme. Either URL containing
 * userInfo or a fragment is treated as malformed/malicious and causes a
 * [LRRPlaintextRefusedException] — that is the audit's `http://attacker.com#@host/`
 * defence.
 *
 * @throws LRRPlaintextRefusedException when either URL has userInfo or a fragment.
 */
internal fun matchesConfiguredServer(
    requestUrl: HttpUrl,
    serverUrlString: String
): ServerMatchResult {
    val serverUrl = serverUrlString.toHttpUrlOrNull()
        ?: throw LRRPlaintextRefusedException("Configured server URL is not a valid HTTP(S) URL")

    if (hasSuspiciousComponents(serverUrl)) {
        throw LRRPlaintextRefusedException(
            "Configured server URL contains userInfo or fragment"
        )
    }
    if (hasSuspiciousComponents(requestUrl)) {
        throw LRRPlaintextRefusedException(
            "Request URL contains userInfo or fragment"
        )
    }

    // HttpUrl.host is already lowercased and bracket-free; HttpUrl.port returns the
    // effective port (defaults applied for the scheme).
    if (requestUrl.host != serverUrl.host) return ServerMatchResult.MISMATCH
    if (requestUrl.port != serverUrl.port) return ServerMatchResult.MISMATCH
    if (requestUrl.scheme != serverUrl.scheme) return ServerMatchResult.SCHEME_DOWNGRADE
    return ServerMatchResult.MATCH
}

/** True when [url] carries userInfo (`user:pass@`) or a fragment (`#…`). */
private fun hasSuspiciousComponents(url: HttpUrl): Boolean {
    return url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.fragment != null
}
