package com.hippo.ehviewer.client.lrr;

import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.URI;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttp interceptor that injects the LANraragi Bearer token ONLY for requests
 * targeting the configured LANraragi server. This prevents leaking the API key
 * to third-party hosts (e.g., external image CDNs or link previews).
 *
 * Token format: Bearer Base64(api_key)
 */
public class LRRAuthInterceptor implements Interceptor {

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        String apiKey = LRRAuthManager.getApiKey();
        Request original = chain.request();

        if (apiKey == null || apiKey.isEmpty()) {
            return chain.proceed(original);
        }

        // Only inject auth header for requests to the configured LANraragi server
        String serverUrl = LRRAuthManager.getServerUrl();
        if (serverUrl == null || !isTargetHost(original.url().toString(), serverUrl)) {
            return chain.proceed(original);
        }

        String token = Base64.encodeToString(apiKey.getBytes("UTF-8"), Base64.NO_WRAP);
        Request authed = original.newBuilder()
                .header("Authorization", "Bearer " + token)
                .build();

        return chain.proceed(authed);
    }

    /**
     * Check if the request URL belongs to the same host:port as the configured server.
     */
    private boolean isTargetHost(@NonNull String requestUrl, @NonNull String serverUrl) {
        try {
            URI reqUri = URI.create(requestUrl);
            URI srvUri = URI.create(serverUrl);
            String reqHost = reqUri.getHost();
            String srvHost = srvUri.getHost();
            if (reqHost == null || srvHost == null) return false;
            if (!reqHost.equalsIgnoreCase(srvHost)) return false;
            // Compare ports (use defaults if not specified)
            int reqPort = reqUri.getPort() != -1 ? reqUri.getPort() : getDefaultPort(reqUri.getScheme());
            int srvPort = srvUri.getPort() != -1 ? srvUri.getPort() : getDefaultPort(srvUri.getScheme());
            return reqPort == srvPort;
        } catch (Exception e) {
            return false;
        }
    }

    private int getDefaultPort(String scheme) {
        if ("https".equalsIgnoreCase(scheme)) return 443;
        if ("http".equalsIgnoreCase(scheme)) return 80;
        return -1;
    }
}
