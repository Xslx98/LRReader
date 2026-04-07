package com.hippo.ehviewer.client.lrr

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp application interceptor that logs a warning when an HTTP (non-HTTPS)
 * request is made to a host other than the configured LANraragi server.
 *
 * The configured server is expected to be on a trusted LAN, so cleartext there
 * is acceptable. Cleartext to any *other* host is suspicious and worth logging.
 *
 * Hardening notes (W0-2): the host comparison is delegated to the same
 * pure-string [matchesConfiguredServer] helper used by [LRRAuthInterceptor].
 * Previously this file performed its own [java.net.InetAddress] resolution,
 * which was vulnerable to the same DNS-rebinding bypass and blocked OkHttp
 * worker threads on synchronous lookups.
 */
class LRRCleartextWarningInterceptor : Interceptor {

    companion object {
        private const val TAG = "LRRCleartext"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // Only care about plaintext HTTP
        if (!"http".equals(url.scheme, ignoreCase = true)) {
            return chain.proceed(request)
        }

        // If it's the configured LRR server, cleartext is expected — skip.
        // Any malformed/malicious server URL (userInfo, fragment) makes the
        // matcher throw; we suppress that here because logging is best-effort
        // and aborting the request is LRRAuthInterceptor's job.
        val serverUrl = LRRAuthManager.getServerUrl()
        if (serverUrl != null) {
            val match = try {
                matchesConfiguredServer(url, serverUrl)
            } catch (_: LRRPlaintextRefusedException) {
                ServerMatchResult.MISMATCH
            }
            if (match == ServerMatchResult.MATCH) {
                return chain.proceed(request)
            }
        }

        Log.w(
            TAG,
            "Cleartext HTTP request to non-LRR host. " +
                "Consider using HTTPS for requests outside the configured server."
        )

        return chain.proceed(request)
    }
}
