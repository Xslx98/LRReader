package com.hippo.ehviewer.module

import android.content.Context
import android.webkit.CookieManager
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Hosts
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhHosts
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages all network-related singletons: OkHttpClient (main + image),
 * HTTP cache, cookie store, proxy selector, and custom DNS hosts.
 * Extracted from EhApplication to reduce its responsibility scope.
 *
 * Internal dependency order:
 *   CookieStore → Cache → Hosts → ProxySelector → OkHttpClient → ImageOkHttpClient
 */
class NetworkModule(private val context: Context) {

    val cookieStore: EhCookieStore by lazy { EhCookieStore(context) }

    val cache: Cache by lazy {
        Cache(File(context.cacheDir, "http_cache"), 100L * 1024L * 1024L)
    }

    val hosts: Hosts by lazy { Hosts(context, "hosts.db") }

    val proxySelector: EhProxySelector by lazy { EhProxySelector() }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
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
                try {
                    val resp = chain.proceed(chain.request())
                    // Force cache LRR thumbnail responses for 24 hours
                    val url = chain.request().url.toString()
                    if (url.contains("/api/archives/") && url.contains("/thumbnail")) {
                        resp.newBuilder()
                            .header("Cache-Control", "public, max-age=86400")
                            .removeHeader("Pragma")
                            .build()
                    } else {
                        resp
                    }
                } catch (e: NullPointerException) {
                    throw NullPointerException(e.message)
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
                    cookieManager.flush()
                }
                response
            }
            .proxySelector(proxySelector)
            .addInterceptor(com.hippo.ehviewer.client.lrr.LRRAuthInterceptor())
            .build()
    }

    val imageOkHttpClient: OkHttpClient by lazy {
        // Derive from main client to share connection pool, thread pool, and SSL config
        okHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Long-read client for archive extraction (large archives can be slow to extract). */
    val longReadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // no overall call timeout for slow servers
            .build()
    }

    /** Upload client for file uploads (large write + long read timeouts). */
    val uploadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .writeTimeout(300, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /** Live connectivity monitor backed by NetworkCallback. */
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(context) }
}
