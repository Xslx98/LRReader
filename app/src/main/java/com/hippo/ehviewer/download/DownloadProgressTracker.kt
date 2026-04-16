/*
 * Copyright 2026 LRReader contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.download

import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the in-memory, per-archive snapshot of transient download progress.
 *
 * Part of ADR-001 Option D (mixed SSOT): progress is memory-only (bytes/s,
 * remaining time, pages done). The DB owns list structure + persistent
 * state; this tracker owns live metrics that reset on app restart.
 *
 * Writes are expected on the main thread (same contract as
 * [DownloadScheduler] / [DownloadRepository]). Reads are lock-free via
 * the backing [MutableStateFlow].
 *
 * Double-write period (W35-3a): callers still mirror values into
 * [com.hippo.ehviewer.dao.DownloadInfo]'s @Ignore fields. W35-3c removes
 * those fields and makes the tracker authoritative.
 */
class DownloadProgressTracker {

    private val _progressFlow = MutableStateFlow<Map<String, ProgressSnapshot>>(emptyMap())

    /** Emits the entire per-arcid map on any change. */
    val progressFlow: StateFlow<Map<String, ProgressSnapshot>> = _progressFlow

    /** Current snapshot for [arcid], or null if none tracked. */
    fun snapshot(arcid: String): ProgressSnapshot? = _progressFlow.value[arcid]

    /**
     * Merge the given non-null fields into the snapshot for [arcid].
     * Creates a new snapshot (with defaults) if absent.
     */
    fun update(
        arcid: String,
        speed: Long? = null,
        finished: Int? = null,
        downloaded: Int? = null,
        total: Int? = null,
        remaining: Long? = null
    ) {
        assertMainThread()
        _progressFlow.value = _progressFlow.value.toMutableMap().apply {
            val current = this[arcid] ?: ProgressSnapshot.initial(arcid)
            put(arcid, current.copyWith(speed, finished, downloaded, total, remaining))
        }
    }

    /**
     * Reset [arcid] to the "just-started" sentinel values that Scheduler
     * writes at the beginning of a download (-1/-1/0/0/-1).
     */
    fun resetForStart(arcid: String) {
        assertMainThread()
        _progressFlow.value = _progressFlow.value.toMutableMap().apply {
            put(arcid, ProgressSnapshot.initial(arcid))
        }
    }

    /** Remove [arcid] from the map. */
    fun clear(arcid: String) {
        assertMainThread()
        if (!_progressFlow.value.containsKey(arcid)) return
        _progressFlow.value = _progressFlow.value.toMutableMap().apply { remove(arcid) }
    }

    /** Remove all entries. Used by [DownloadManager.reload]. */
    fun clearAll() {
        assertMainThread()
        if (_progressFlow.value.isEmpty()) return
        _progressFlow.value = emptyMap()
    }

    private fun assertMainThread() {
        // Unit tests run without an Android Looper; skip the check when no
        // main looper is available (tests exercise single-threaded).
        val mainLooper = Looper.getMainLooper() ?: return
        check(Looper.myLooper() == mainLooper) {
            "DownloadProgressTracker must be mutated on the main thread, current: ${Thread.currentThread().name}"
        }
    }
}
