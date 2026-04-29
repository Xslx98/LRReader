/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.client.api

import android.util.LruCache
import com.hippo.ehviewer.module.Cacheable

/**
 * In-memory LRU cache for LANraragi `GET /api/archives/:id/files`
 * responses. Keyed by `(activeProfileId, arcid)` so switching server
 * profiles doesn't surface stale paths from a different host.
 *
 * **Why in-memory and not Room:**
 * The file list is *server-authoritative, mutable* state — an
 * archive's pages can be re-extracted, renamed, or replaced server-
 * side. Persisting it would create a second source of truth that
 * needs invalidation whenever metadata changes, which our model
 * intentionally avoids (the archive_json blob is overwritten on
 * every metadata fetch and we don't want similar cache-coherency
 * complexity at the file-list level). A short TTL + bounded LRU
 * gives the cold-open speedup we need for the detail→reader
 * handoff without inheriting that whole problem.
 *
 * **Lifetime:**
 * Cleared on profile switch via [Cacheable.clearCache] (registered
 * in [com.hippo.ehviewer.module.ClientModule]). Entries also expire
 * after [TTL_MS] — long enough to bridge "open detail → tap read"
 * but short enough that an out-of-band server change recovers
 * within minutes without manual intervention.
 */
object LrrFileListCache : Cacheable {

    /** TTL for cached entries. */
    private const val TTL_MS: Long = 30 * 60 * 1000L // 30 minutes

    /**
     * Max distinct (profile, arcid) entries held simultaneously.
     * 32 covers the typical "user browses a handful of details
     * then dives into one to read"; surplus entries are evicted
     * LRU-style.
     */
    private const val MAX_ENTRIES = 32

    private data class Entry(val pages: Array<String>, val storedAt: Long)

    private val lru = LruCache<String, Entry>(MAX_ENTRIES)

    private fun keyFor(profileId: Long, arcid: String): String = "$profileId|$arcid"

    /**
     * Look up the cached file list for [arcid] under the active
     * server profile. Returns null on miss or expiry.
     */
    fun get(arcid: String): Array<String>? {
        val profileId = LRRAuthManager.getActiveProfileId()
        val key = keyFor(profileId, arcid)
        val entry = synchronized(lru) { lru.get(key) } ?: return null
        if (System.currentTimeMillis() - entry.storedAt > TTL_MS) {
            synchronized(lru) { lru.remove(key) }
            return null
        }
        return entry.pages
    }

    /** Store [pages] for [arcid] under the active server profile. */
    fun put(arcid: String, pages: Array<String>) {
        val profileId = LRRAuthManager.getActiveProfileId()
        val key = keyFor(profileId, arcid)
        synchronized(lru) { lru.put(key, Entry(pages, System.currentTimeMillis())) }
    }

    /**
     * Remove a single arcid from the cache. Use after a server-side
     * mutation (re-extract / delete) the caller already knows about.
     */
    fun invalidate(arcid: String) {
        val profileId = LRRAuthManager.getActiveProfileId()
        val key = keyFor(profileId, arcid)
        synchronized(lru) { lru.remove(key) }
    }

    override fun clearCache() {
        synchronized(lru) { lru.evictAll() }
    }
}
