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
 * Aggregate [ProgressSnapshot] behind a downloads-list tank card (spec
 * 2026-09-21 §4): finished / total pages, speed and in-flight page
 * fractions summed over the members that have a live snapshot with a
 * known total. Members without one contribute nothing (the bar is honest
 * about what is actually known); no contributing member → null, and the
 * card falls back to its textual state line.
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
            val snap = snapshotOf(member.arcid) ?: continue
            if (snap.total <= 0) continue
            any = true
            finished += snap.finished.coerceAtLeast(0)
            total += snap.total
            speed += snap.speed.coerceAtLeast(0L)
            partial += snap.partialPages.coerceAtLeast(0f)
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
