package com.hippo.ehviewer.module

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Hosts
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhHosts
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages all network-related singletons: OkHttpClient (main + image),
 * HTTP cache, cookie store, proxy selector, and custom DNS hosts.
 * Extracted from EhApplication to reduce its responsibility scope.
 *
 * Internal dependency order:
 *   CookieStore → Cache → Hosts → ProxySelector → OkHttpClient → ImageOkHttpClient
 */
class NetworkModule(private val context: Context) {

    /** Debounce flag: ensures only one CookieManager.flush() is scheduled at a time. */
    private val cookieFlushPending = AtomicBoolean(false)

    val cookieStore: EhCookieStore by lazy { EhCookieStore(context) }

    val cache: Cache by lazy {
        Cache(File(context.cacheDir, "http_cache"), 200L * 1024L * 1024L)
    }

    val hosts: Hosts by lazy { Hosts(context, "hosts.db") }

    val proxySelector: EhProxySelector by lazy { EhProxySelector() }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieStore)
            .cache(cache)
            .dns(EhHosts(context))
            .addNetworkInterceptor { chain ->
                val resp = chain.proceed(chain.request())
                // Force cache LRR thumbnail responses for 24 hours
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
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // Sync cookies to WebView
                val setCookieHeaders = response.headers("Set-Cookie")
                if (setCookieHeaders.isNotEmpty()) {
                    val cookieManager = CookieManager.getInstance()
                    val url = chain.request().url.toString()
                    for (header in setCookieHeaders) {
                        cookieManager.setCookie(url, header)
                    }
                    // Debounced flush: schedule at most one flush per burst of requests
                    if (cookieFlushPending.compareAndSet(false, true)) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                cookieManager.flush()
                            } finally {
                                cookieFlushPending.set(false)
                            }
                        }, 3000)
                    }
                }
                response
            }
            .proxySelector(proxySelector)
            .addInterceptor(com.hippo.ehviewer.client.lrr.LRRCleartextWarningInterceptor())
            .addNetworkInterceptor(com.hippo.ehviewer.client.lrr.LRRAuthInterceptor())
            .build()
    }

    val imageOkHttpClient: OkHttpClient by lazy {
        // Derive from main client to share connection pool, thread pool, and SSL config
        okHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Long-read client for archive extraction (large archives can be slow to extract). */
    val longReadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES) // extraction should never exceed 10 min
            .build()
    }

    /** Upload client for file uploads (large write + long read timeouts). */
    val uploadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .writeTimeout(300, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES) // allow large archives on slow WAN
            .build()
    }

    /** Live connectivity monitor backed by NetworkCallback. */
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(context) }
}
