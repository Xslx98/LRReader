package com.lanraragi.reader.module

/**
 * Interface for components that hold in-memory or disk caches which
 * must be cleared when the active server profile changes.
 *
 * Implementors register themselves via [com.lanraragi.reader.ServiceRegistry.registerCacheable]
 * and are invoked by [com.lanraragi.reader.ServiceRegistry.clearAllCaches].
 */
interface Cacheable {

    /**
     * Clear all cached data owned by this component.
     * Implementations must be safe to call from any thread and must not throw.
     */
    fun clearCache()
}
