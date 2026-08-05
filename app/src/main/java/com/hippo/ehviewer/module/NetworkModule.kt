package com.hippo.ehviewer.module

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhProxySelector
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages all network-related singletons: OkHttpClient (main + image),
 * HTTP cache, and proxy selector.
 * Extracted from EhApplication to reduce its responsibility scope.
 *
 * Internal dependency order:
 *   Cache → ProxySelector → OkHttpClient → ImageOkHttpClient
 *
 * DNS uses OkHttp's default [okhttp3.Dns.SYSTEM]; LANraragi servers are
 * resolved through the platform DNS like any other host.
 *
 * LANraragi uses Bearer-token auth, so the OkHttp client is configured with
 * [CookieJar.NO_COOKIES]: no cookies are stored, sent, or persisted.
 */
class NetworkModule(private val context: Context) : INetworkModule, Cacheable {

    companion object {
        private const val TAG = "NetworkModule"
    }

    override val cache: Cache by lazy {
        Cache(File(context.cacheDir, "http_cache"), 200L * 1024L * 1024L)
    }

    override val proxySelector: EhProxySelector by lazy { EhProxySelector() }

    /**
     * Shared dispatcher. The OkHttp default caps concurrent requests to the
     * same host at 5, which throttled parallel page downloads once multiple
     * archives ran at once (or when a single archive exceeded
     * `PARALLEL_PAGES=4`). LANraragi's Hypnotoad (4 workers × 1000
     * connections) tolerates far more; raise the per-host cap so our own
     * parallelism settings actually take effect.
     */
    private val dispatcher: Dispatcher by lazy {
        Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 16
        }
    }

    override val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(cache)
            // Thumbnail freshness headers (successful responses only — see
            // ThumbnailCacheControlInterceptor for the error-pinning rationale).
            .addNetworkInterceptor(com.lanraragi.reader.client.api.ThumbnailCacheControlInterceptor())
            .proxySelector(proxySelector)
            // Cleartext gate must be a NETWORK interceptor, not an application
            // one: with followRedirects(true) an HTTPS→HTTP redirect is a new
            // hop that an application interceptor (runs once, on the original
            // request) never sees, silently downgrading a cleartext-disabled
            // profile to plain HTTP. A network interceptor re-evaluates every
            // hop. Placed before the auth interceptor so a rejected hop never
            // gets an API key attached.
            .addNetworkInterceptor(com.lanraragi.reader.client.api.LRRCleartextRejectionInterceptor())
            .addNetworkInterceptor(com.lanraragi.reader.client.api.LRRAuthInterceptor())
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

    /** Cached instance of the interface-default page-streaming client. */
    override val pageStreamClient: OkHttpClient by lazy { super.pageStreamClient }

    /** Cached instance of the interface-default thumbnail-fetch client. */
    override val thumbFetchClient: OkHttpClient by lazy { super.thumbFetchClient }

    /** Cached instance of the interface-default large-file client. */
    override val largeFileClient: OkHttpClient by lazy { super.largeFileClient }

    /** Live connectivity monitor backed by NetworkCallback. */
    override val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(context) }

    override fun clearCache() {
        try { cache.evictAll() } catch (e: Exception) { Log.w(TAG, "Failed to evict HTTP cache", e) }
    }
}
