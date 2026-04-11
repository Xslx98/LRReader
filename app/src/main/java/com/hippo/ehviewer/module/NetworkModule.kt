package com.hippo.ehviewer.module

import android.content.Context
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Hosts
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages all network-related singletons: OkHttpClient (main + image),
 * HTTP cache, and proxy selector.
 * Extracted from EhApplication to reduce its responsibility scope.
 *
 * Internal dependency order:
 *   Cache → Hosts → ProxySelector → OkHttpClient → ImageOkHttpClient
 *
 * DNS uses OkHttp's default [okhttp3.Dns.SYSTEM]; LANraragi servers are
 * resolved through the platform DNS like any other host.
 *
 * LANraragi uses Bearer-token auth, so the OkHttp client is configured with
 * [CookieJar.NO_COOKIES]: no cookies are stored, sent, or persisted.
 */
class NetworkModule(private val context: Context) : INetworkModule, Cacheable {

    override val cache: Cache by lazy {
        Cache(File(context.cacheDir, "http_cache"), 200L * 1024L * 1024L)
    }

    override val hosts: Hosts by lazy { Hosts(context, "hosts.db") }

    override val proxySelector: EhProxySelector by lazy { EhProxySelector() }

    override val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(cache)
            .addNetworkInterceptor { chain ->
                val resp = chain.proceed(chain.request())
                // LANraragi does not send Cache-Control headers on thumbnail
                // responses, so inject them here.
                // URL pattern: {baseUrl}/api/archives/{arcid}/thumbnail (no query params)
                // max-age=3600 → fresh for 1 h; stale-while-revalidate=82800 → serve
                // stale while revalidating for the remaining 23 h (24 h total).
                val url = chain.request().url.toString()
                if (url.contains("/api/archives/") && url.contains("/thumbnail")) {
                    resp.newBuilder()
                        .header("Cache-Control", "public, max-age=3600, stale-while-revalidate=82800")
                        .removeHeader("Pragma")
                        .build()
                } else {
                    resp
                }
            }
            .proxySelector(proxySelector)
            .addInterceptor(com.lanraragi.reader.client.api.LRRCleartextRejectionInterceptor())
            .addNetworkInterceptor(com.lanraragi.reader.client.api.LRRAuthInterceptor())
            .build()
    }

    override val imageOkHttpClient: OkHttpClient by lazy {
        // Derive from main client to share connection pool, thread pool, and SSL config
        okHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Long-read client for archive extraction (large archives can be slow to extract). */
    override val longReadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES) // extraction should never exceed 10 min
            .build()
    }

    /** Upload client for file uploads (large write + long read timeouts). */
    override val uploadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .writeTimeout(300, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES) // allow large archives on slow WAN
            .build()
    }

    /** Live connectivity monitor backed by NetworkCallback. */
    override val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(context) }

    override fun clearCache() {
        try { cache.evictAll() } catch (_: Exception) {}
    }
}
