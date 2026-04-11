package com.lanraragi.reader.client.api

import android.util.Log
import com.lanraragi.reader.client.api.data.LRRTagStat
import com.hippo.ehviewer.module.Cacheable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cache for LANraragi tag statistics from /api/database/stats.
 *
 * The cache is populated asynchronously and queried synchronously for
 * fast tag suggestions in the search bar. A 10-minute TTL triggers a
 * background refresh on the next access after expiration.
 */
object LRRTagCache : Cacheable {

    private const val TAG = "LRRTagCache"

    private data class CacheSnapshot(
        val tags: List<LRRTagStat>,
        val searchKeys: List<String>
    )

    @Volatile
    private var snapshot: CacheSnapshot = CacheSnapshot(emptyList(), emptyList())

    @Volatile
    private var lastFetchTime: Long = 0

    private const val TTL_MS = 10 * 60 * 1000L // 10 minutes

    /** Prevents concurrent network refreshes. */
    private val refreshMutex = Mutex()

    /** Meta-namespaces to exclude from suggestions. */
    private val EXCLUDED_NAMESPACES = setOf("date_added", "source")

    /**
     * Fetch tag stats from the server if the cache is empty or stale.
     * Safe to call from a coroutine; network I/O happens inside [LRRDatabaseApi].
     * Uses a mutex with double-check to prevent concurrent refreshes.
     *
     * @return the cached tag list (may be stale if the fetch fails)
     */
    suspend fun getTags(): List<LRRTagStat> {
        if (needsRefresh()) {
            refreshMutex.withLock {
                // Double-check after acquiring lock
                if (needsRefresh()) {
                    try {
                        val fetched = LRRDatabaseApi.getTagStats()
                        val keys = fetched.map { "${it.namespace}:${it.text}".lowercase() }
                        snapshot = CacheSnapshot(fetched, keys)
                        lastFetchTime = System.currentTimeMillis()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch tag stats: ${e.message}")
                        // Return stale cache or empty list
                    }
                }
            }
        }
        return snapshot.tags
    }

    /**
     * @return true if the cache has been populated at least once.
     */
    fun isPopulated(): Boolean = snapshot.tags.isNotEmpty()

    /**
     * @return true if the cache needs a refresh (empty or expired).
     */
    fun needsRefresh(): Boolean =
        snapshot.tags.isEmpty() || System.currentTimeMillis() - lastFetchTime > TTL_MS

    /**
     * Synchronous, in-memory filter of cached tags.
     * No network I/O — safe to call from the UI thread.
     * Uses precomputed [searchKeys] for efficient matching.
     *
     * @param keyword the user's search input
     * @param limit maximum number of results to return
     * @return matching tags sorted by weight (descending)
     */
    fun suggest(keyword: String, limit: Int = 20): List<LRRTagStat> {
        if (keyword.isBlank()) return emptyList()
        val lower = keyword.lowercase()
        val snap = snapshot
        val currentTags = snap.tags
        val currentKeys = snap.searchKeys
        val results = mutableListOf<LRRTagStat>()
        for (i in currentTags.indices) {
            val tag = currentTags[i]
            if (tag.namespace in EXCLUDED_NAMESPACES) continue
            if (i < currentKeys.size && currentKeys[i].contains(lower)) {
                results.add(tag)
            }
        }
        results.sortByDescending { it.weight }
        if (results.size > limit) {
            return results.take(limit)
        }
        return results
    }

    /**
     * Clear the cache. Called when switching server profiles so that
     * tags from the previous server do not appear as suggestions.
     */
    fun clear() {
        snapshot = CacheSnapshot(emptyList(), emptyList())
        lastFetchTime = 0
    }

    override fun clearCache() {
        clear()
    }
}
