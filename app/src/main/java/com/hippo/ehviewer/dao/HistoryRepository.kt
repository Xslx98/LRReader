package com.hippo.ehviewer.dao

import com.hippo.ehviewer.mapper.toArchive
import com.hippo.ehviewer.mapper.toArchiveJson
import com.hippo.ehviewer.mapper.toHistoryInfoView
import com.hippo.ehviewer.settings.AppearanceSettings
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.domain.Archive

/**
 * Repository for history-related database operations, backed (post-L1)
 * by [ArchiveLocalStateDao] / the unified `ARCHIVE_LOCAL_STATE` table.
 *
 * Public surface is unchanged from the v22-era repository — callers
 * keep talking in [HistoryInfo] and [Archive], the new storage shape
 * is a concealed implementation detail.
 *
 * **Atomicity note**: each public method composes one or more atomic
 * SQL statements but does NOT wrap them in `withTransaction`. The
 * pattern is INSERT-OR-IGNORE-then-UPDATE for upserts, and
 * clear-then-deleteIfEmpty for removals; concurrent same-arcid
 * operations are rare and the worst observable race is a transient
 * stale row that the next operation collapses. Avoiding
 * `withTransaction` is also necessary in unit tests where Room's
 * inline executors otherwise leak the transaction's coroutine context
 * into the invalidation-tracker observer that drives [observeAllDownloads]
 * and friends.
 *
 * Registered as a lazy val in [com.hippo.ehviewer.module.DataModule].
 */
class HistoryRepository(
    private val dao: ArchiveLocalStateDao,
    @Suppress("UNUSED_PARAMETER") database: AppDatabase,
) {

    suspend fun getHistoryLazyList(): List<HistoryInfo> {
        val profileId = LRRAuthManager.getActiveProfileId()
        val rows = if (profileId > 0) dao.getHistoryByServer(profileId) else dao.getAllHistory()
        return rows.map { it.toHistoryInfoView() }
    }

    suspend fun putHistoryInfo(archive: Archive) {
        val now = System.currentTimeMillis()
        upsertHistorySubsystem(
            arcid = archive.arcid,
            serverProfileId = archive.serverProfileId,
            archiveJson = archive.copy(lastreadtime = now).toArchiveJson(),
            historyTime = now,
            historyMode = 0,
        )
        trimHistory(listOf(archive.serverProfileId))
    }

    suspend fun putHistoryInfoList(historyInfoList: List<HistoryInfo>) {
        if (historyInfoList.isEmpty()) return
        for (info in historyInfoList) {
            upsertHistorySubsystem(
                arcid = info.arcid,
                serverProfileId = info.serverProfileId,
                archiveJson = info.toArchive().toArchiveJson(),
                historyTime = info.time,
                historyMode = info.mode,
            )
        }
        trimHistory(historyInfoList.map { it.serverProfileId })
    }

    suspend fun deleteHistoryInfo(info: HistoryInfo) {
        deleteHistory(info.arcid, info.serverProfileId)
    }

    /**
     * Deletes a single history entry by its `(arcid, serverProfileId)`
     * composite key — the row identity of `ARCHIVE_LOCAL_STATE` (ADR-003).
     * Idempotent: a missing row is a no-op.
     */
    suspend fun deleteHistory(arcid: String, profileId: Long) {
        dao.clearHistorySubsystemForProfile(arcid, profileId)
        dao.deleteIfNoSubsystemForProfile(arcid, profileId)
    }

    suspend fun clearHistory() {
        dao.clearAllHistorySubsystems()
        dao.deleteAllEmptyRows()
    }

    /**
     * Update the rating for a history entry identified by [arcid].
     * The rating lives in `archive_json` — load, patch, write back.
     */
    suspend fun updateRating(arcid: String, profileId: Long, rating: Float) {
        val row = dao.loadByArcidAndProfile(arcid, profileId) ?: return
        val archive = ArchiveLocalStateJson.decodeFromString(Archive.serializer(), row.archiveJson)
        dao.updateArchiveJsonForProfile(arcid, profileId, archive.copy(rating = rating).toArchiveJson())
    }

    /**
     * Persist the intra-page scroll fraction (0.0 ~ 1.0) for [arcid].
     * Local-only — never goes to the LANraragi server. The DAO update
     * is a no-op if the archive doesn't yet have a history row, which
     * is fine: the next call after the row is created will land.
     */
    suspend fun setHistoryScrollFraction(arcid: String, profileId: Long, fraction: Float?) {
        dao.updateHistoryScrollFractionForProfile(arcid, profileId, fraction)
    }

    suspend fun getHistoryScrollFraction(arcid: String, profileId: Long): Float? =
        dao.getHistoryScrollFractionForProfile(arcid, profileId)

    /**
     * Idempotent INSERT-OR-IGNORE-then-UPDATE. The IGNORE step
     * preserves any pre-existing download / favorite columns by
     * leaving an existing row alone; the UPDATE then writes the
     * history columns whether the row was new or existing.
     */
    private suspend fun upsertHistorySubsystem(
        arcid: String,
        serverProfileId: Long,
        archiveJson: String,
        historyTime: Long,
        historyMode: Int,
    ) {
        dao.insertOrIgnoreHistory(arcid, serverProfileId, archiveJson, historyTime, historyMode)
        dao.updateHistoryFields(arcid, serverProfileId, archiveJson, historyTime, historyMode)
    }

    private suspend fun trimHistory(profileIds: Collection<Long>) {
        val maxCount = AppearanceSettings.getHistoryInfoSize().let {
            if (it < 1) DEFAULT_HISTORY_MAX else it
        }
        for (pid in profileIds.distinct()) {
            dao.clearHistorySubsystemBeyondForProfile(pid, maxCount)
        }
        dao.deleteAllEmptyRows()
    }

    private companion object {
        const val DEFAULT_HISTORY_MAX = 100
    }
}
