package com.hippo.ehviewer.client.lrr

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.net.InetAddress
import java.net.URI

/**
 * OkHttp application interceptor that logs a warning when an HTTP (non-HTTPS)
 * request is made to a host other than the configured LANraragi server.
 *
 * The configured server is expected to be on a trusted LAN, so cleartext there
 * is acceptable. Cleartext to any *other* host is suspicious and worth logging.
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

        // If it's the configured LRR server, cleartext is expected — skip
        val serverUrl = LRRAuthManager.getServerUrl()
        if (serverUrl != null && isConfiguredServer(url.toString(), serverUrl)) {
            return chain.proceed(request)
        }

        Log.w(
            TAG,
            "Cleartext HTTP request to non-LRR host: ${url.host}. " +
                "Consider using HTTPS for requests outside the configured server."
        )

        return chain.proceed(request)
    }

    /**
     * Check if the request URL targets the same host:port as the configured server.
     * Reuses the same InetAddress normalization logic as [LRRAuthInterceptor].
     */
    private fun isConfiguredServer(requestUrl: String, serverUrl: String): Boolean {
        return try {
            val reqUri = URI.create(requestUrl)
            val srvUri = URI.create(serverUrl)
            val reqHost = reqUri.host ?: return false
            val srvHost = srvUri.host ?: return false

            val hostsMatch = try {
                InetAddress.getByName(reqHost) == InetAddress.getByName(srvHost)
            } catch (_: Exception) {
                reqHost.equals(srvHost, ignoreCase = true)
            }
            if (!hostsMatch) return false

            val reqPort = if (reqUri.port != -1) reqUri.port else defaultPort(reqUri.scheme)
            val srvPort = if (srvUri.port != -1) srvUri.port else defaultPort(srvUri.scheme)
            reqPort == srvPort
        } catch (_: Exception) {
            false
        }
    }

    private fun defaultPort(scheme: String?): Int = when {
        "https".equals(scheme, ignoreCase = true) -> 443
        "http".equals(scheme, ignoreCase = true) -> 80
        else -> -1
    }
}
