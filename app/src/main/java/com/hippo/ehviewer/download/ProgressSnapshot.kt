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

/**
 * Immutable snapshot of a download's transient progress fields.
 *
 * Produced by [DownloadScheduler] / [DownloadSpeedTracker] and stored in
 * [DownloadProgressTracker]'s StateFlow map keyed by arcid. Not persisted.
 *
 * Part of ADR-001 Option D (mixed SSOT): progress lives in memory, the DB
 * only owns list structure + persistent fields (state/legacy/label/time).
 */
data class ProgressSnapshot(
    val arcid: String,
    val speed: Long = -1L,
    val finished: Int = 0,
    val downloaded: Int = 0,
    val total: Int = -1,
    val remaining: Long = -1L
) {
    /**
     * Return a copy with only the given fields replaced. Any argument left as
     * `null` preserves the current value.
     */
    fun copyWith(
        speed: Long? = null,
        finished: Int? = null,
        downloaded: Int? = null,
        total: Int? = null,
        remaining: Long? = null
    ): ProgressSnapshot = ProgressSnapshot(
        arcid = arcid,
        speed = speed ?: this.speed,
        finished = finished ?: this.finished,
        downloaded = downloaded ?: this.downloaded,
        total = total ?: this.total,
        remaining = remaining ?: this.remaining
    )

    companion object {
        /** Sentinel "just queued" snapshot matching the values Scheduler uses on start. */
        fun initial(arcid: String): ProgressSnapshot = ProgressSnapshot(arcid = arcid)
    }
}
