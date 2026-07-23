package com.hippo.ehviewer.module

import com.hippo.ehviewer.EhProxySelector
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Abstraction over [NetworkModule] to allow ServiceRegistry consumers to depend on the
 * contract rather than the concrete implementation. Enables test-time substitution with
 * mock OkHttp clients and in-memory caches.
 */
interface INetworkModule {

    /** Disk HTTP cache backing [okHttpClient] and derived clients. */
    val cache: Cache

    /** Proxy selector respecting Settings-driven proxy configuration. */
    val proxySelector: EhProxySelector

    /** Main HTTP client used for API calls and short-read operations. */
    val okHttpClient: OkHttpClient

    /** HTTP client tuned for image downloads (longer read/call timeouts). */
    val imageOkHttpClient: OkHttpClient

    /** HTTP client tuned for archive extraction (long read, 10-min call timeout). */
    val longReadClient: OkHttpClient

    /** HTTP client tuned for archive uploads (long write, 30-min call timeout). */
    val uploadClient: OkHttpClient

    /**
     * HTTP client for streaming page images (reader, page preload, download
     * worker). No call cap — a large page or an on-the-fly server extraction
     * can legitimately exceed the shared 30s callTimeout; readTimeout still
     * catches genuine stalls. The HTTP cache is disabled: page bodies are
     * multi-MB, already cached on disk by their consumers (ReaderPageCache /
     * the download dir), and would otherwise churn thumbnails out of the
     * shared 200 MB HTTP cache.
     *
     * Default getter derives per access so INetworkModule test fakes need no
     * override; [NetworkModule] overrides with a cached instance.
     */
    val pageStreamClient: OkHttpClient
        get() = okHttpClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .cache(null)
            .build()

    /**
     * HTTP client for large single-file transfers (APK update, translation
     * DB, database stats dumps). No call cap so a slow link can't abort a
     * multi-MB body mid-stream; 60s readTimeout catches stalls.
     *
     * Default getter derives per access so INetworkModule test fakes need no
     * override; [NetworkModule] overrides with a cached instance.
     */
    val largeFileClient: OkHttpClient
        get() = okHttpClient.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    /** Live connectivity monitor backed by NetworkCallback. */
    val networkMonitor: NetworkMonitor
}
