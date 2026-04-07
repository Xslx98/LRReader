package com.hippo.ehviewer.module

import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Hosts
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * Abstraction over [NetworkModule] to allow ServiceRegistry consumers to depend on the
 * contract rather than the concrete implementation. Enables test-time substitution with
 * mock OkHttp clients and in-memory caches.
 */
interface INetworkModule {

    /** Disk HTTP cache backing [okHttpClient] and derived clients. */
    val cache: Cache

    /** Custom DNS host overrides used by [EhHosts][com.hippo.ehviewer.client.EhHosts]. */
    val hosts: Hosts

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

    /** Live connectivity monitor backed by NetworkCallback. */
    val networkMonitor: NetworkMonitor
}
