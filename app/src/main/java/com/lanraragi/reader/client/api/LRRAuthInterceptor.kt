package com.lanraragi.reader.client.api

import android.util.Base64
import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import com.lanraragi.reader.server.ProfileLookupCache
import com.lanraragi.reader.server.ProfileUrlCandidate
import com.lanraragi.reader.server.ServerSession

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
 *  - A request issued before the Room flow's first emission waits for it
 *    ([ProfileLookupCache.awaitLoadedBlocking]): the profiles are the only
 *    source of which hosts get a key.
 */
class LRRAuthInterceptor(
    /** The profile lookup; may throw or be null while the app is still booting. */
    private val profileLookupCache: () -> ProfileLookupCache?,
) : Interceptor {

    companion object {
        private const val TAG = "LRRAuthInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val cache = runCatching(profileLookupCache)
            .getOrNull()
        cache?.awaitLoadedBlocking()
        val candidates = cache?.findParsedCandidatesByHostPort(
            original.url.host, original.url.port,
        ).orEmpty()

        // A third-party host: no profile owns it, no key goes with it.
        if (candidates.isEmpty()) return chain.proceed(original)

        if (candidates.none { it.url.scheme == original.url.scheme }) {
            Log.e(TAG, "Refusing to send API key: scheme mismatch with configured profile")
            throw LRRPlaintextRefusedException(
                "Scheme mismatch between request and configured profile"
            )
        }
        val match = pickSchemeMatch(candidates, original.url)
        if (match == null) {
            // Same host:port but outside every profile's base path (another
            // app on the host, or a server-supplied URL escaping its
            // instance): no key goes with it (audit C48 / SEC-08).
            return chain.proceed(original)
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

        val apiKey = ServerSession.getApiKeyForProfile(match.profile.id)
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
 * Pick the candidate whose configured scheme matches [requestUrl] and
 * whose base path best matches its path. Returns null when no candidate
 * has both the scheme and a base path owning the request path; the caller
 * rejects a scheme mismatch and sends no key otherwise. Candidates carry
 * their pre-parsed URL, so this is pure string comparison.
 */
internal fun pickSchemeMatch(
    candidates: List<ProfileUrlCandidate>,
    requestUrl: HttpUrl,
): ProfileUrlCandidate? {
    val sameScheme = candidates.filter { it.url.scheme == requestUrl.scheme }
    if (sameScheme.isEmpty()) return null
    // Several LANraragi instances can share one host behind a reverse proxy
    // (https://nas/lrr-a, https://nas/lrr-b): pick the profile whose base
    // path is the longest prefix of the request path, so each instance gets
    // its own key. Ties prefer the active profile. No prefix match means no
    // key: falling back to the first candidate handed one instance's key to
    // any path on the host (audit C48 / SEC-08).
    val requestPath = requestUrl.encodedPath
    return sameScheme
        .filter { isPathPrefix(basePath(it.url), requestPath) }
        .maxWithOrNull(compareBy<ProfileUrlCandidate>({ basePath(it.url).length }, { it.profile.isActive }))
}

/** A profile URL's path without its trailing slash ("" for the root). */
private fun basePath(url: HttpUrl): String = url.encodedPath.trimEnd('/')

/** Whether [prefix] is [path] or one of its parent paths, on segment boundaries. */
private fun isPathPrefix(prefix: String, path: String): Boolean =
    prefix.isEmpty() || path == prefix || path.startsWith("$prefix/")

/** True when [url] carries userInfo (`user:pass@`) or a fragment (`#…`). */
private fun hasSuspiciousComponents(url: HttpUrl): Boolean {
    return url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.fragment != null
}
