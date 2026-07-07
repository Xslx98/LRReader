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

import android.content.Context
import kotlin.math.max

/**
 * Pure winner-selection for reading-progress reconciliation between the
 * local SharedPreferences save (device clock) and the LANraragi server's
 * `progress` / `lastreadtime` (server clock).
 *
 * Both timestamps are epoch seconds but come from DIFFERENT clocks, and
 * `lastreadtime` is set when the server processes our own progress PUT —
 * i.e. always a beat after the local save. A naive `serverTs > localTs`
 * comparison therefore makes the server win on almost every reopen, so a
 * stale racing PUT (the providers' putStartPage fires a fire-and-forget
 * updateProgress PUT per page turn) used to move the resume position
 * backwards. Within [CLOCK_SKEW_GRACE_SECONDS] the two saves are
 * treated as the same reading session and the furthest page wins; beyond
 * it, the clearly-newer side wins (genuine cross-device reading).
 */
internal object ReadingProgressReconciler {

    /**
     * A PUT round-trip alone only justifies a few seconds of skew; 120 s is
     * sized for un-NTP'd LAN/NAS server clock drift. Genuine cross-device
     * sessions are minutes-to-days apart, so the window stays safely between
     * the two scales: sub-120 s is "same session, possibly stale PUT";
     * over-120 s is "real independent read on another device".
     */
    const val CLOCK_SKEW_GRACE_SECONDS = 120L

    /**
     * @param localPage0 0-indexed page from the local SP save
     * @param localTs epoch seconds of the local save (0 = none)
     * @param serverProgress 1-indexed `progress` from server metadata (<=0 = none)
     * @param serverTs epoch seconds `lastreadtime` from server metadata
     * @return resolved 0-indexed page; may exceed the provider's actual page
     *   count when server metadata is stale or a partial local download is in
     *   progress — callers' apply paths clamp or reject out-of-range values
     */
    fun resolve(localPage0: Int, localTs: Long, serverProgress: Int, serverTs: Long): Int {
        if (serverProgress <= 0) return localPage0
        val serverPage0 = serverProgress - 1 // early return above guarantees serverProgress >= 1
        val delta = serverTs - localTs
        return when {
            delta > CLOCK_SKEW_GRACE_SECONDS -> serverPage0
            delta < -CLOCK_SKEW_GRACE_SECONDS && localPage0 > 0 -> localPage0
            else -> max(serverPage0, localPage0)
        }
    }

    /**
     * Any epoch value above this is clearly milliseconds, not seconds
     * (100_000_000_000 s ≈ year 5138). Stored `archive_json` snapshots are a
     * historical mixed bag: server fetches persist epoch-seconds, while the
     * history write path stamps `System.currentTimeMillis()` — see
     * [normalizeEpochSeconds].
     */
    private const val MS_EPOCH_THRESHOLD = 100_000_000_000L

    /**
     * Normalize a snapshot `lastreadtime` to epoch seconds. Feeding a
     * milliseconds value into [resolve]'s ±[CLOCK_SKEW_GRACE_SECONDS] math
     * makes the snapshot side win unconditionally — a stale snapshot would
     * beat a genuinely-newer local save.
     */
    fun normalizeEpochSeconds(ts: Long): Long =
        if (ts > MS_EPOCH_THRESHOLD) ts / 1000L else ts

    /**
     * Offline best-guess start page: reconcile the locally-saved SP progress
     * against the Room archive snapshot's server progress
     * ([com.lanraragi.reader.domain.Archive.progress] /
     * [com.lanraragi.reader.domain.Archive.lastreadtime]) with the same math
     * as the online restore. Single source of truth for every reader warm-up
     * trigger AND [DirGalleryProvider]'s start-page seed — warm target, seed
     * and slot-consume index must agree for the decoded-page hand-off to hit.
     *
     * @param snapshotProgress1 1-indexed progress from the archive snapshot (<=0 = none)
     * @param snapshotTs epoch seconds `lastreadtime` from the archive snapshot
     *   (milliseconds tolerated — normalized via [normalizeEpochSeconds])
     * @return 0-indexed page
     */
    fun resolveOffline(
        context: Context,
        arcid: String,
        snapshotProgress1: Int,
        snapshotTs: Long,
    ): Int = resolve(
        GalleryProvider2.loadReadingProgress(context, arcid),
        GalleryProvider2.loadReadingTimestamp(context, arcid),
        snapshotProgress1,
        normalizeEpochSeconds(snapshotTs),
    )
}
