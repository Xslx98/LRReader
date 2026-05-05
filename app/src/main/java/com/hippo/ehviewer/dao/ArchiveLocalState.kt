/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.hippo.ehviewer.download.DownloadState

/**
 * Unified per-archive local state row. Replaces the three near-identical
 * tables (`DOWNLOADS`, `HISTORY`, `LOCAL_FAVORITES`) that previously
 * carried the same display columns alongside their own subsystem flags.
 *
 * One row per archive. Subsystem membership is encoded by NOT NULL on a
 * dedicated key column:
 *
 * | Subsystem | Predicate                       |
 * |-----------|---------------------------------|
 * | Download  | `DOWNLOAD_STATE IS NOT NULL`    |
 * | History   | `HISTORY_TIME IS NOT NULL`      |
 * | Favorite  | `FAVORITE_TIME IS NOT NULL`     |
 *
 * Display payload (title / thumbnail / rating / tags / …) lives entirely
 * in the [archiveJson] column. The on-wire shape is the kotlinx
 * serialization of [com.lanraragi.reader.domain.Archive] — adding new
 * display fields no longer requires a Room schema bump (forward-compat
 * via `ignoreUnknownKeys`).
 *
 * The L1 plan introducing this entity is at
 * `docs/archive/plans/2026-04-28-l1-archive-local-state-unification.md`
 * (gitignored). Migration `MIGRATION_22_23` lifts existing rows from
 * the three legacy tables into this one.
 */
@Entity(
    tableName = "ARCHIVE_LOCAL_STATE",
    primaryKeys = ["ARCID"],
    indices = [
        Index("SERVER_PROFILE_ID"),
        Index("DOWNLOAD_TIME"),
        Index("HISTORY_TIME"),
        Index("FAVORITE_TIME"),
        Index("DOWNLOAD_LABEL"),
    ],
)
data class ArchiveLocalState(
    @ColumnInfo(name = "ARCID")
    val arcid: String,

    @ColumnInfo(name = "SERVER_PROFILE_ID", defaultValue = "0")
    val serverProfileId: Long = 0L,

    @ColumnInfo(name = "ARCHIVE_JSON")
    val archiveJson: String,

    // ── Download subsystem ─────────────────────────────────────
    /** Non-null iff the archive is in the download list. */
    @ColumnInfo(name = "DOWNLOAD_STATE")
    val downloadState: DownloadState? = null,

    @ColumnInfo(name = "DOWNLOAD_LEGACY", defaultValue = "0")
    val downloadLegacy: Int = 0,

    @ColumnInfo(name = "DOWNLOAD_TIME")
    val downloadTime: Long? = null,

    @ColumnInfo(name = "DOWNLOAD_LABEL")
    val downloadLabel: String? = null,

    @ColumnInfo(name = "DOWNLOAD_ARCHIVE_URI")
    val downloadArchiveUri: String? = null,

    /**
     * SAF tree URI of the download root in effect when this archive was
     * first persisted to the download subsystem. Resolves the
     * "user changed download path after the archive was downloaded"
     * problem: read paths (gallery viewer, resume worker, delete) prefer
     * this stored URI over the current `DownloadSettings.getDownloadLocation()`,
     * so an old download stays reachable at its original tree even after
     * the setting flips. NULL on:
     *   - rows lifted from pre-v26 databases before the boot backfill runs
     *     (see EhApplication startup), and
     *   - rows where the download was added before the column existed and
     *     the user has since cleared the download location.
     * NULL is the "use current setting" sentinel.
     */
    @ColumnInfo(name = "DOWNLOAD_ROOT_URI")
    val downloadRootUri: String? = null,

    // ── History subsystem ──────────────────────────────────────
    /** Non-null iff the archive has reading history. */
    @ColumnInfo(name = "HISTORY_TIME")
    val historyTime: Long? = null,

    @ColumnInfo(name = "HISTORY_MODE", defaultValue = "0")
    val historyMode: Int = 0,

    /**
     * Per-archive intra-page scroll position (0.0 ~ 1.0) for vertical
     * long-strip reading. NULL means "no precise resume point — fall
     * back to the page top". Updated when the reader exits a page in
     * `LAYOUT_TOP_TO_BOTTOM` mode; ignored in pager modes. Local-only
     * (LANraragi has no equivalent server field), so it stays a
     * dedicated column rather than living in [archiveJson] which gets
     * overwritten by every server resync.
     */
    @ColumnInfo(name = "HISTORY_SCROLL_FRACTION")
    val historyScrollFraction: Float? = null,

    // ── Favorite subsystem ─────────────────────────────────────
    /** Non-null iff the archive is in local favorites. */
    @ColumnInfo(name = "FAVORITE_TIME")
    val favoriteTime: Long? = null,
)
