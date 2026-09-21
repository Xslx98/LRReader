/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.download

import com.lanraragi.reader.dao.DownloadInfo

/**
 * Aggregate [ProgressSnapshot] behind a downloads-list tank card: the WHOLE
 * tank as one download. Per member, in order of authority:
 * - a live snapshot with a known total (the member being downloaded);
 * - a FINISH member: its persisted page count, fully done;
 * - any other member (queued / failed / partial) with a persisted page
 *   count: 0 done of that count;
 * - a member with no snapshot and no page count contributes nothing (the
 *   bar is honest about what is actually known).
 * Speed and in-flight page fractions come from live snapshots only. No
 * contributing member → null, and the card falls back to its textual
 * state line.
 *
 * Read-only over the tracker's published snapshots — never a new emission
 * channel (ADR-001 §11).
 */
object TankProgressAggregate {

    fun of(
        tankId: String,
        members: List<DownloadInfo>,
        snapshotOf: (String) -> ProgressSnapshot?,
    ): ProgressSnapshot? {
        var finished = 0
        var total = 0
        var speed = 0L
        var partial = 0f
        var any = false
        for (member in members) {
            val snap = snapshotOf(member.arcid)
            when {
                snap != null && snap.total > 0 -> {
                    finished += snap.finished.coerceAtLeast(0)
                    total += snap.total
                    speed += snap.speed.coerceAtLeast(0L)
                    partial += snap.partialPages.coerceAtLeast(0f)
                }
                member.pagecount > 0 -> {
                    total += member.pagecount
                    if (member.state == DownloadState.FINISH) finished += member.pagecount
                }
                else -> continue
            }
            any = true
        }
        if (!any) return null
        return ProgressSnapshot(
            arcid = tankId,
            speed = speed,
            finished = finished,
            total = total,
            partialPages = partial,
        )
    }
}
