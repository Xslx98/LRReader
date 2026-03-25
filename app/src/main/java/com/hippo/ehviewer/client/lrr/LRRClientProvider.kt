package com.hippo.ehviewer.client.lrr

import android.content.Context
import com.hippo.ehviewer.EhApplication
import okhttp3.OkHttpClient

/**
 * Centralized provider for OkHttpClient and server base URL.
 *
 * All LRR API classes should obtain their client and base URL from here
 * instead of having callers pass them in. This ensures consistent
 * configuration (auth headers, timeouts, caching) across all API calls.
 *
 * Usage:
 * ```
 * LRRClientProvider.init(context)
 * val client = LRRClientProvider.getClient()
 * val baseUrl = LRRClientProvider.getBaseUrl()
 * ```
 */
object LRRClientProvider {

    @Volatile
    private var appContext: Context? = null

    /**
     * Initialize with application context. Called once from EhApplication.onCreate().
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    @JvmStatic
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /**
     * Get the shared OkHttpClient configured with LRRAuthInterceptor.
     * This delegates to [EhApplication.getOkHttpClient] to ensure
     * there is only one OkHttpClient instance in the entire app.
     *
     * @throws IllegalStateException if [init] has not been called
     */
    @JvmStatic
    fun getClient(): OkHttpClient {
        val ctx = appContext
            ?: throw IllegalStateException("LRRClientProvider not initialized. Call init(context) first.")
        return EhApplication.getOkHttpClient(ctx)
    }

    /**
     * Get the currently configured server base URL.
     *
     * @return base URL string (e.g., "http://192.168.1.100:3000"), or empty string if not configured
     */
    @JvmStatic
    fun getBaseUrl(): String {
        return LRRAuthManager.getServerUrl() ?: ""
    }

    /**
     * @return true if a server URL has been configured in [LRRAuthManager]
     */
    @JvmStatic
    fun isConfigured(): Boolean {
        return LRRAuthManager.isConfigured()
    }
}
