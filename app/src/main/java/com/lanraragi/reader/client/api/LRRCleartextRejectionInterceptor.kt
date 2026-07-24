package com.lanraragi.reader.client.api

import com.hippo.ehviewer.ServiceRegistry
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Authoritative cleartext gate for LANraragi Reader.
 *
 * Android cannot enforce per-host cleartext whitelist for arbitrary LAN IPs at the
 * OS level (`<domain-config>` accepts hostnames but not CIDR/wildcard IPs, and
 * once `cleartextTrafficPermitted="false"` is declared, OkHttp refuses cleartext
 * BEFORE any application interceptor runs). So `network_security_config.xml`
 * keeps cleartext globally allowed and this interceptor enforces the real policy:
 *
 *   - HTTPS                                                              → allow
 *   - HTTP, host:port matches a configured profile, scheme matches,
 *     and that profile's allowCleartext == true                          → allow
 *   - HTTP, host:port matches a configured profile, scheme matches,
 *     but allowCleartext == false                                        → reject
 *   - HTTP, host:port matches a configured profile, scheme differs       → reject
 *   - HTTP, no profile matches host:port (cache empty / cold-start) →
 *     fall back to legacy active-only check against [LRRAuthManager]
 *
 * Reject = throw [LRRCleartextRefusedException], a subclass of [java.io.IOException]
 * so OkHttp surfaces it as a normal network failure handled by existing
 * `try/catch (IOException)` paths.
 *
 * Comparison is done as **pure string match** on host (lowercased) and on the
 * effective port (URL's `port` field, which OkHttp normalises to the scheme
 * default automatically). No `InetAddress.getByName` — that would add IO and
 * a DNS-based bypass surface (DNS rebinding).
 */
class LRRCleartextRejectionInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url: HttpUrl = request.url

        // 1. HTTPS always passes — TLS validation is OkHttp's job, not ours.
        if (url.scheme.equals("https", ignoreCase = true)) {
            return chain.proceed(request)
        }

        // 2. Find configured profiles owning this host:port (any scheme).
        val cache = runCatching { ServiceRegistry.dataModule.profileLookupCache }
            .getOrNull()
        val candidates = cache?.findParsedCandidatesByHostPort(url.host, url.port).orEmpty()

        if (candidates.isNotEmpty()) {
            return enforceProfileBased(chain, request, url, candidates)
        }

        // 3. Cache cold-start or unconfigured host. Fall back to legacy
        //    active-only matching so requests issued before the Room flow
        //    delivers its first emission still pass policy gates correctly.
        return enforceActiveBased(chain, request, url)
    }

    private fun enforceProfileBased(
        chain: Interceptor.Chain,
        request: okhttp3.Request,
        url: HttpUrl,
        candidates: List<ProfileUrlCandidate>,
    ): Response {
        // Scheme equality: pick the candidate whose configured URL is also
        // HTTP. Candidates carry their pre-parsed URL — no re-parse here.
        val match = candidates.firstOrNull {
            it.url.scheme.equals("http", ignoreCase = true)
        }
        if (match == null) {
            // Host:port matches a profile but every candidate is HTTPS —
            // request is attempting cleartext on an HTTPS-configured profile.
            throw LRRCleartextRefusedException(
                "Cleartext request refused: configured profile is HTTPS, request is HTTP."
            )
        }
        if (!match.profile.allowCleartext) {
            throw LRRCleartextRefusedException(
                "Cleartext request refused: profile does not allow plain HTTP."
            )
        }
        return chain.proceed(request)
    }

    private fun enforceActiveBased(
        chain: Interceptor.Chain,
        request: okhttp3.Request,
        url: HttpUrl,
    ): Response {
        val serverUrlStr = LRRAuthManager.getServerUrl()
            ?: throw LRRCleartextRefusedException(
                "Cleartext request refused: no active LANraragi server configured."
            )

        val serverUrl = serverUrlStr.toHttpUrlOrNull()
            ?: throw LRRCleartextRefusedException(
                "Cleartext request refused: configured server URL is malformed."
            )

        // Active server scheme must match the request scheme.
        if (!serverUrl.scheme.equals("http", ignoreCase = true)) {
            throw LRRCleartextRefusedException(
                "Cleartext request refused: active server is HTTPS, request is HTTP."
            )
        }

        if (!url.host.equals(serverUrl.host, ignoreCase = true)) {
            throw LRRCleartextRefusedException(
                "Cleartext request refused: host does not match active server."
            )
        }

        if (url.port != serverUrl.port) {
            throw LRRCleartextRefusedException(
                "Cleartext request refused: port does not match active server."
            )
        }

        if (!LRRAuthManager.getAllowCleartext()) {
            throw LRRCleartextRefusedException(
                "Cleartext request refused: profile does not allow plain HTTP."
            )
        }

        return chain.proceed(request)
    }
}
