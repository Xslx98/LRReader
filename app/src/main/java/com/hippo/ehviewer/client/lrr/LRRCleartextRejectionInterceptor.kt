package com.hippo.ehviewer.client.lrr

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
 *   - HTTPS                                                → allow
 *   - HTTP, no active server configured                    → reject
 *   - HTTP, host:port doesn't match active server          → reject
 *   - HTTP, scheme doesn't match active server (downgrade) → reject
 *   - HTTP, host:port matches but allowCleartext == false  → reject
 *   - HTTP, host:port matches and allowCleartext == true   → allow
 *
 * Reject = throw [LRRCleartextRefusedException], a subclass of [java.io.IOException]
 * so OkHttp surfaces it as a normal network failure handled by existing
 * `try/catch (IOException)` paths.
 *
 * Comparison is done as **pure string match** on host (lowercased) and on the
 * effective port (URL's `port` field, which OkHttp normalises to the scheme
 * default automatically). No `InetAddress.getByName` — that would add IO and
 * a DNS-based bypass surface (DNS rebinding). The active server URL is read
 * synchronously via [LRRAuthManager.getServerUrl] / [LRRAuthManager.getAllowCleartext].
 */
class LRRCleartextRejectionInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url: HttpUrl = request.url

        // 1. HTTPS always passes — TLS validation is OkHttp's job, not ours.
        if (url.scheme.equals("https", ignoreCase = true)) {
            return chain.proceed(request)
        }

        // 2. Cleartext requires an active server profile.
        val serverUrlStr = LRRAuthManager.getServerUrl()
            ?: throw LRRCleartextRefusedException(
                "Cleartext request refused: no active LANraragi server configured."
            )

        val serverUrl = serverUrlStr.toHttpUrlOrNull()
            ?: throw LRRCleartextRefusedException(
                "Cleartext request refused: configured server URL is malformed."
            )

        // 3. Active server scheme must match the request scheme.
        //    (Prevents an attacker from downgrading https://lrr.local to http://lrr.local.)
        if (!serverUrl.scheme.equals("http", ignoreCase = true)) {
            throw LRRCleartextRefusedException(
                "Cleartext request refused: active server is HTTPS, request is HTTP."
            )
        }

        // 4. Host comparison (case-insensitive). HttpUrl.host is already lowercased
        //    by OkHttp for ASCII hostnames; the explicit equals(ignoreCase) keeps
        //    things obvious.
        if (!url.host.equals(serverUrl.host, ignoreCase = true)) {
            throw LRRCleartextRefusedException(
                "Cleartext request refused: host does not match active server."
            )
        }

        // 5. Port comparison. HttpUrl.port returns the explicit port if set,
        //    otherwise the default for the scheme (80 for http). Both URLs are
        //    parsed by HttpUrl so the comparison is normalized.
        if (url.port != serverUrl.port) {
            throw LRRCleartextRefusedException(
                "Cleartext request refused: port does not match active server."
            )
        }

        // 6. Per-profile opt-in.
        if (!LRRAuthManager.getAllowCleartext()) {
            throw LRRCleartextRefusedException(
                "Cleartext request refused: profile does not allow plain HTTP."
            )
        }

        return chain.proceed(request)
    }
}
