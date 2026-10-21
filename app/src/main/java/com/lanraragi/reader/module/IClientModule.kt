package com.lanraragi.reader.module

import coil3.ImageLoader

/**
 * Abstraction over [ClientModule] to allow ServiceRegistry consumers to depend on the
 * contract rather than the concrete implementation. Enables test-time substitution.
 */
interface IClientModule {

    /**
     * Coil image loader for archive covers and thumbnails, backed by the
     * network module's thumbnail client so the auth interceptor and server
     * trust apply.
     */
    val coverImageLoader: ImageLoader

    /** Clears the cover memory cache. Used under memory pressure. */
    fun clearMemoryCache()
}
