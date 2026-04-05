package com.hippo.ehviewer.client.lrr

import android.util.Log
import com.hippo.ehviewer.client.lrr.data.LRRTagStat

/**
 * In-memory cache for LANraragi tag statistics from /api/database/stats.
 *
 * The cache is populated asynchronously and queried synchronously for
 * fast tag suggestions in the search bar. A 10-minute TTL triggers a
 * background refresh on the next access after expiration.
 */
object LRRTagCache {

    private const val TAG = "LRRTagCache"

    @Volatile
    private var tags: List<LRRTagStat> = emptyList()

    @Volatile
    private var lastFetchTime: Long = 0

    private const val TTL_MS = 10 * 60 * 1000L // 10 minutes

    /** Meta-namespaces to exclude from suggestions. */
    private val EXCLUDED_NAMESPACES = setOf("date_added", "source")

    /**
     * Fetch tag stats from the server if the cache is empty or stale.
     * Safe to call from a coroutine; network I/O happens inside [LRRDatabaseApi].
     *
     * @return the cached tag list (may be stale if the fetch fails)
     */
    suspend fun getTags(): List<LRRTagStat> {
        if (tags.isEmpty() || System.currentTimeMillis() - lastFetchTime > TTL_MS) {
            try {
                tags = LRRDatabaseApi.getTagStats()
                lastFetchTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch tag stats: ${e.message}")
                // Return stale cache or empty list
            }
        }
        return tags
    }

    /**
     * @return true if the cache has been populated at least once.
     */
    fun isPopulated(): Boolean = tags.isNotEmpty()

    /**
     * @return true if the cache needs a refresh (empty or expired).
     */
    fun needsRefresh(): Boolean =
        tags.isEmpty() || System.currentTimeMillis() - lastFetchTime > TTL_MS

    /**
     * Synchronous, in-memory filter of cached tags.
     * No network I/O — safe to call from the UI thread.
     *
     * @param keyword the user's search input
     * @param limit maximum number of results to return
     * @return matching tags sorted by weight (descending)
     */
    fun suggest(keyword: String, limit: Int = 20): List<LRRTagStat> {
        if (keyword.isBlank()) return emptyList()
        val lower = keyword.lowercase()
        return tags.asSequence()
            .filter { it.namespace !in EXCLUDED_NAMESPACES }
            .filter {
                it.text.lowercase().contains(lower) ||
                    "${it.namespace}:${it.text}".lowercase().contains(lower)
            }
            .sortedByDescending { it.weight }
            .take(limit)
            .toList()
    }

    /**
     * Clear the cache. Called when switching server profiles so that
     * tags from the previous server do not appear as suggestions.
     */
    fun clear() {
        tags = emptyList()
        lastFetchTime = 0
    }
}
