package com.lanraragi.reader.module

import android.content.Context
import android.util.Log
import com.lanraragi.reader.AppProxySelector
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
 * Extracted from LRReaderApplication to reduce its responsibility scope.
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
        Cache(File(context.cacheDir, "http_cache"), com.lanraragi.reader.util.CacheBudget.http(context))
    }

    override val proxySelector: AppProxySelector by lazy { AppProxySelector() }

    /**
     * Shared dispatcher. Its limits apply only to enqueue()d calls (API requests);
     * page traffic (download worker, reader cache, thumbnails) uses blocking
     * execute() and is capped by those callers instead — see
     * LRRDownloadWorker.PARALLEL_PAGES and ForegroundReading (audit PERF-13).
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

    /**
     * Long-read client for archive extraction (large archives can be slow to
     * extract). Off the shared HTTP cache like every big-body client: its
     * responses (file lists, page streams) are single-use (audit C35).
     */
    override val longReadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .cache(null)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES) // extraction should never exceed 10 min
            .build()
    }

    /** Upload client for file uploads (large write + long read timeouts, no HTTP cache). */
    override val uploadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .cache(null)
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
