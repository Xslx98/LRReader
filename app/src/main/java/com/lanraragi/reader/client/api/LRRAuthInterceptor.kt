package com.lanraragi.reader.client.api

import android.util.Base64
import android.util.Log
import com.hippo.ehviewer.ServiceRegistry
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that injects the LANraragi Bearer token for requests
 * targeting any **configured** LANraragi server profile. Resolves the
 * profile synchronously through [ProfileLookupCache] so cross-profile
 * downloads (e.g. background worker on profile A while UI is on profile B)
 * use the correct credentials without the user having to switch.
 *
 * Token format: Bearer Base64(api_key)
 *
 * Hardening notes:
 *  - Match logic is **pure string equality** on host + port + scheme. No DNS
 *    resolution happens here, so DNS rebinding cannot bypass the host check
 *    and OkHttp worker threads are never blocked on synchronous lookups.
 *  - Scheme participates in matching: a request whose host+port matches a
 *    profile but whose scheme differs is treated as a credential-downgrade
 *    attempt and aborted with [LRRPlaintextRefusedException] before the
 *    token leaves the device.
 *  - URLs containing userInfo (`user:pass@`) or a fragment (`#`) are rejected
 *    outright.
 *  - When the cache is cold (boot edge: app started but the Room flow has
 *    not delivered its first emission yet), falls back to legacy active-only
 *    matching against [LRRAuthManager.getServerUrl] so requests issued
 *    during the first few ms of process life still authenticate correctly.
 */
class LRRAuthInterceptor : Interceptor {

    companion object {
        private const val TAG = "LRRAuthInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val cache = runCatching { ServiceRegistry.dataModule.profileLookupCache }
            .getOrNull()
        val candidates = cache?.findParsedCandidatesByHostPort(
            original.url.host, original.url.port,
        ).orEmpty()

        if (candidates.isEmpty()) {
            // Either a third-party host (no profile owns it — pass through),
            // or the cache is still cold. Fall back to legacy active-only
            // matching so cold-start requests still authenticate.
            return interceptViaActive(chain)
        }

        val match = pickSchemeMatch(candidates, original.url)
        if (match == null) {
            Log.e(TAG, "Refusing to send API key: scheme mismatch with configured profile")
            throw LRRPlaintextRefusedException(
                "Scheme mismatch between request and configured profile"
            )
        }

        if (hasSuspiciousComponents(original.url)) {
            throw LRRPlaintextRefusedException("Request URL contains userInfo or fragment")
        }
        // match.url is the profile URL pre-parsed by ProfileLookupCache; an
        // unparseable profile URL never becomes a candidate (same as the
        // old per-lookup `?: continue`), so no null-parse branch remains.
        if (hasSuspiciousComponents(match.url)) {
            throw LRRPlaintextRefusedException(
                "Configured profile URL contains userInfo or fragment"
            )
        }

        val apiKey = LRRAuthManager.getApiKeyForProfile(match.profile.id)
        if (apiKey.isNullOrEmpty()) {
            // Profile is configured but has no per-profile key (legacy or
            // intentionally open server). Pass through without injecting;
            // the server will respond with 401 if it actually requires auth.
            return chain.proceed(original)
        }

        val authed = original.newBuilder()
            .header("Authorization", bearerAuthHeaderValue(apiKey))
            .build()
        return chain.proceed(authed)
    }

    /**
     * Legacy fallback path: match against [LRRAuthManager.getServerUrl] and
     * inject [LRRAuthManager.getApiKey]. Preserves the pre-cache behaviour
     * for boot-edge requests and for tests that wire only the auth-manager
     * singleton without populating the Room SERVER_PROFILES table.
     */
    private fun interceptViaActive(chain: Interceptor.Chain): Response {
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
                val authed = original.newBuilder()
                    .header("Authorization", bearerAuthHeaderValue(apiKey))
                    .build()
                return chain.proceed(authed)
            }
            ServerMatchResult.SCHEME_DOWNGRADE -> {
                Log.e(TAG, "Refusing to send API key: scheme mismatch with configured server")
                throw LRRPlaintextRefusedException(
                    "Scheme mismatch between request and configured server"
                )
            }
            ServerMatchResult.MISMATCH -> {
                return chain.proceed(original)
            }
        }
    }
}

/**
 * The single LANraragi authorization header value: `Bearer Base64(apiKey)`
 * with [Base64.NO_WRAP] so the token stays on one line. Shared by
 * [LRRAuthInterceptor] and the connection-test probe
 * ([LRRServerApi.getServerInfo]) so the wire format can never drift.
 */
internal fun bearerAuthHeaderValue(apiKey: String): String {
    val token = Base64.encodeToString(apiKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return "Bearer $token"
}

/**
 * Pick the candidate whose configured scheme matches [requestUrl].
 * Returns null when every candidate has the wrong scheme (a
 * credential-downgrade attempt — caller should reject). Candidates carry
 * their pre-parsed URL, so this is pure string comparison.
 */
internal fun pickSchemeMatch(
    candidates: List<ProfileUrlCandidate>,
    requestUrl: HttpUrl,
): ProfileUrlCandidate? {
    for (candidate in candidates) {
        if (candidate.url.scheme == requestUrl.scheme) return candidate
    }
    return null
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
