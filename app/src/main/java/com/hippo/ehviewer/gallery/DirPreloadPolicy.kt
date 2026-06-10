/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.gallery

/**
 * Reader-anchored preload window for [DirGalleryProvider].
 *
 * A successful decode of page [decodedIndex] may enqueue up to [radius]
 * following pages — but never past `anchor + radius`, where [anchor] is
 * the page the reader is actually on. Without that bound each preload
 * decode spawned the next preloads and the chain marched from the start
 * page to the end of the archive, evicting the reader's neighbourhood
 * from the shared LRU image cache (the intermittent "loading page after
 * a page turn" on downloaded archives) while burning CPU on pages the
 * user may never reach. A decode that finished far from the anchor in
 * EITHER direction (stale queued work from before a jump) must not spawn
 * new preloads.
 */
internal object DirPreloadPolicy {

    fun preloadTargets(
        decodedIndex: Int,
        anchor: Int,
        totalPages: Int,
        radius: Int,
        isCached: (Int) -> Boolean,
    ): List<Int> {
        // A decode that finished more than `radius` behind the anchor is
        // stale (e.g. queued before a forward jump) — spawning from it
        // would march the chain across the gap to the anchor window.
        if (decodedIndex + radius < anchor) return emptyList()
        val limit = anchor + radius
        val targets = mutableListOf<Int>()
        for (i in 1..radius) {
            val next = decodedIndex + i
            if (next >= totalPages) break
            if (next > limit) break
            if (isCached(next)) continue
            targets.add(next)
        }
        return targets
    }
}
