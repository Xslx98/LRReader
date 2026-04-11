package com.hippo.ehviewer.module

/**
 * Interface for components that hold in-memory or disk caches which
 * must be cleared when the active server profile changes.
 *
 * Implementors register themselves via [com.hippo.ehviewer.ServiceRegistry.registerCacheable]
 * and are invoked by [com.hippo.ehviewer.ServiceRegistry.clearAllCaches].
 */
interface Cacheable {

    /**
     * Clear all cached data owned by this component.
     * Implementations must be safe to call from any thread and must not throw.
     */
    fun clearCache()
}
