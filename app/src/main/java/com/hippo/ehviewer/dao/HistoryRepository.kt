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
 * **Atomicity note (DB-4)**: statement pairs (INSERT-OR-IGNORE-then-
 * UPDATE upserts, clear-then-deleteIfEmpty removals) go through the
 * DAO's `@Transaction` pair-wrappers so an interleaving writer cannot
 * drop a subsystem row. Repository-level `withTransaction` remains
 * forbidden: it would leak the transaction's coroutine context into
 * the invalidation-tracker observer that drives [observeAllDownloads]
 * and friends in unit tests — Room's generated `@Transaction` wrapper
 * does not have that problem.
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
            // archive_json `lastreadtime` is epoch SECONDS (LANraragi
            // semantics — the reconciler compares it against SP saves);
            // only the HISTORY_TIME column keeps device milliseconds.
            archiveJson = archive.copy(lastreadtime = now / 1000L).toArchiveJson(),
            historyTime = now,
            historyMode = 0,
        )
        trimHistory(listOf(archive.serverProfileId))
    }

    suspend fun putHistoryInfoList(historyInfoList: List<HistoryInfo>) {
        if (historyInfoList.isEmpty()) return
        // Single DAO transaction: a legacy import of N rows is one commit,
        // not N (the per-row loop paid N fsyncs).
        dao.upsertHistoryBatch(
            historyInfoList.map { info ->
                HistoryUpsertRow(
                    arcid = info.arcid,
                    serverProfileId = info.serverProfileId,
                    archiveJson = info.toArchive().toArchiveJson(),
                    historyTime = info.time,
                    historyMode = info.mode,
                )
            }
        )
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
        dao.clearHistoryAndPruneForProfile(arcid, profileId)
    }

    suspend fun clearHistory() {
        dao.clearAllHistoryAndPruneEmptyRows()
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
     * Read the persisted archive snapshot (the row's `archive_json`) for
     * [arcid] on [profileId], or null when there is no local row or the
     * payload fails to decode. The reader's warm-up/seed path overlays its
     * `progress`/`lastreadtime` when the caller's Archive went through a
     * lossy view mapper (DownloadInfo/HistoryInfo.toArchive() deliberately
     * zero `progress` and carry no server progress pair — those round-trip
     * via detail fetches).
     */
    suspend fun getArchiveSnapshot(arcid: String, profileId: Long): Archive? {
        val row = dao.loadByArcidAndProfile(arcid, profileId) ?: return null
        return runCatching {
            ArchiveLocalStateJson.decodeFromString(Archive.serializer(), row.archiveJson)
        }.getOrNull()
    }

    /**
     * Cross-profile history rows with their decoded snapshots, for the
     * reading-statistics page (issue #18). A row whose `archive_json` fails
     * to decode still counts (null archive) so totals stay honest.
     */
    suspend fun getAllHistoryStatsRows(): List<HistoryStatsRow> =
        dao.getAllHistory().map { row ->
            HistoryStatsRow(
                arcid = row.arcid,
                serverProfileId = row.serverProfileId,
                historyTime = row.historyTime,
                archive = runCatching {
                    ArchiveLocalStateJson.decodeFromString(Archive.serializer(), row.archiveJson)
                }.getOrNull(),
            )
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
        dao.upsertHistory(arcid, serverProfileId, archiveJson, historyTime, historyMode)
    }

    private suspend fun trimHistory(profileIds: Collection<Long>) {
        val maxCount = AppearanceSettings.getHistoryInfoSize().let {
            if (it < 1) DEFAULT_HISTORY_MAX else it
        }
        for (pid in profileIds.distinct()) {
            dao.trimHistoryForProfile(pid, maxCount)
        }
    }

    private companion object {
        const val DEFAULT_HISTORY_MAX = 100
    }
}
