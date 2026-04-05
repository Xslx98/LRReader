package com.hippo.ehviewer.client.lrr

import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI

/**
 * OkHttp interceptor that injects the LANraragi Bearer token ONLY for requests
 * targeting the configured LANraragi server. This prevents leaking the API key
 * to third-party hosts (e.g., external image CDNs or link previews).
 *
 * Token format: Bearer Base64(api_key)
 */
class LRRAuthInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val apiKey = LRRAuthManager.getApiKey()
        val original = chain.request()

        if (apiKey.isNullOrEmpty()) {
            return chain.proceed(original)
        }

        // Only inject auth header for requests to the configured LANraragi server
        val serverUrl = LRRAuthManager.getServerUrl()
        if (serverUrl == null || !isTargetHost(original.url.toString(), serverUrl)) {
            return chain.proceed(original)
        }

        val token = Base64.encodeToString(apiKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val authed = original.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return chain.proceed(authed)
    }

    /**
     * Check if the request URL belongs to the same host:port as the configured server.
     */
    private fun isTargetHost(requestUrl: String, serverUrl: String): Boolean {
        return try {
            val reqUri = URI.create(requestUrl)
            val srvUri = URI.create(serverUrl)
            val reqHost = reqUri.host ?: return false
            val srvHost = srvUri.host ?: return false
            if (!reqHost.equals(srvHost, ignoreCase = true)) return false
            // Compare ports (use defaults if not specified)
            val reqPort = if (reqUri.port != -1) reqUri.port else getDefaultPort(reqUri.scheme)
            val srvPort = if (srvUri.port != -1) srvUri.port else getDefaultPort(srvUri.scheme)
            reqPort == srvPort
        } catch (_: Exception) {
            false
        }
    }

    private fun getDefaultPort(scheme: String?): Int = when {
        "https".equals(scheme, ignoreCase = true) -> 443
        "http".equals(scheme, ignoreCase = true) -> 80
        else -> -1
    }
}
