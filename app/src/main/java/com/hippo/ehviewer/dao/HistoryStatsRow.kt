package com.hippo.ehviewer.dao

import com.lanraragi.reader.domain.Archive

/**
 * One cross-profile history row for statistics derivation (issue #18):
 * the raw identifiers + canonical-ms [historyTime] plus the decoded
 * `archive_json` snapshot ([archive] is null when the payload fails to
 * decode — the row still counts toward totals).
 */
data class HistoryStatsRow(
    val arcid: String,
    val serverProfileId: Long,
    val historyTime: Long?,
    val archive: Archive?,
)
