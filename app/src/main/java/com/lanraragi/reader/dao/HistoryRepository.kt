package com.lanraragi.reader.dao

import com.lanraragi.reader.mapper.toArchive
import com.lanraragi.reader.mapper.toArchiveJson
import com.lanraragi.reader.mapper.toHistoryInfoView
import com.lanraragi.reader.settings.AppearanceSettings
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
 * Registered as a lazy val in [com.lanraragi.reader.module.DataModule].
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
            },
            ::mergeSnapshotJson,
        )
        trimHistory(historyInfoList.map { it.serverProfileId })
    }

    /** Outcome of [foldMembersIntoTank]: which member rows lost their history flag. */
    class TankFoldResult(val foldedArcids: List<String>, val tankHistoryTime: Long)

    /**
     * Fold the history rows of a tankoubon's members into ONE TANK_ pseudo
     * row (spec 2026-09-21 §3: members have no standalone identity). Members
     * of [profileId] holding a history row are cleared (download / favorite
     * flags on the same row survive, row pruned only when empty); the tank
     * row is created from [tankPseudo] at the newest member read time, or —
     * when a tank row already exists — only has its time bumped forward
     * (its archive_json is session-written truth and is kept). No member
     * history → null, nothing written.
     */
    suspend fun foldMembersIntoTank(
        tankId: String,
        profileId: Long,
        memberIds: List<String>,
        tankPseudo: Archive,
    ): TankFoldResult? {
        val members = memberIds.mapNotNull { arcid ->
            dao.loadByArcidAndProfile(arcid, profileId)?.takeIf { it.historyTime != null }
        }
        if (members.isEmpty()) return null
        val newest = members.maxOf { it.historyTime ?: 0L }
        val existing = dao.loadByArcidAndProfile(tankId, profileId)?.takeIf { it.historyTime != null }
        val tankTime = maxOf(newest, existing?.historyTime ?: 0L)
        val json = existing?.archiveJson
            ?: tankPseudo.copy(lastreadtime = tankTime / 1000L).toArchiveJson()
        if (existing == null || tankTime != existing.historyTime) {
            upsertHistorySubsystem(tankId, profileId, json, tankTime, existing?.historyMode ?: 0)
        }
        for (row in members) dao.clearHistoryAndPruneForProfile(row.arcid, profileId)
        return TankFoldResult(members.map { it.arcid }, tankTime)
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

    /**
     * Clear the history the History list shows: the active profile's
     * (other servers' history is not on screen and must survive), or every
     * profile's when none is active — mirroring [getHistoryLazyList].
     */
    suspend fun clearHistory() {
        val profileId = LRRAuthManager.getActiveProfileId()
        if (profileId > 0) {
            dao.clearHistoryForProfileAndPruneEmptyRows(profileId)
        } else {
            dao.clearAllHistoryAndPruneEmptyRows()
        }
    }

    /**
     * Record where a reading session ended: the snapshot's progress pair
     * becomes [progress1] (1-indexed) at "now", as the server records it
     * after our progress PUT. No-op without a row.
     */
    suspend fun recordSessionProgress(arcid: String, profileId: Long, progress1: Int) {
        if (progress1 <= 0) return
        val now = System.currentTimeMillis() / 1000L
        dao.patchArchiveJsonForProfile(arcid, profileId) { json ->
            decodeOrNull(json)?.copy(progress = progress1, lastreadtime = now)?.toArchiveJson()
        }
    }

    /**
     * Update the rating for a history entry identified by [arcid].
     * The rating lives in `archive_json` — load, patch, write back.
     */
    suspend fun updateRating(arcid: String, profileId: Long, rating: Float) {
        dao.patchArchiveJsonForProfile(arcid, profileId) { json ->
            decodeOrNull(json)?.copy(rating = rating)?.toArchiveJson()
        }
    }

    /**
     * Zero the archive snapshot's progress pair (`progress`/`lastreadtime`)
     * for [arcid] on [profileId]. Part of the "reset reading progress" flow:
     * the offline reconciler ([com.lanraragi.reader.gallery.ReadingProgressReconciler])
     * reads this snapshot, so leaving the old pair in place would resurrect
     * the pre-reset resume position. Rows that are missing or fail to decode
     * are skipped.
     */
    suspend fun resetReadingProgress(arcid: String, profileId: Long) {
        dao.patchArchiveJsonForProfile(arcid, profileId) { json ->
            decodeOrNull(json)?.copy(progress = 0, lastreadtime = 0L)?.toArchiveJson()
        }
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
        dao.upsertHistoryBatch(
            listOf(HistoryUpsertRow(arcid, serverProfileId, archiveJson, historyTime, historyMode)),
            ::mergeSnapshotJson,
        )
    }

    private suspend fun trimHistory(profileIds: Collection<Long>) {
        val maxCount = AppearanceSettings.getHistoryInfoSize().let {
            if (it < 1) DEFAULT_HISTORY_MAX else it
        }
        for (pid in profileIds.distinct()) {
            dao.trimHistoryForProfile(pid, maxCount)
        }
    }

    internal companion object {
        private const val DEFAULT_HISTORY_MAX = 100

        /**
         * Overlay an incoming history snapshot onto the stored one. Fields
         * a lossy view cannot know (HistoryInfo/DownloadInfo.toArchive()
         * zero pagecount and progress and drop the summary) never erase a
         * stored value; everything the incoming snapshot does know wins.
         */
        fun mergeSnapshot(existing: Archive, incoming: Archive): Archive = incoming.copy(
            title = incoming.title.ifBlank { existing.title },
            thumbnailUrl = incoming.thumbnailUrl.ifBlank { existing.thumbnailUrl },
            extension = incoming.extension.ifBlank { existing.extension },
            filename = incoming.filename.ifBlank { existing.filename },
            tags = incoming.tags.ifEmpty { existing.tags },
            pagecount = if (incoming.pagecount > 0) incoming.pagecount else existing.pagecount,
            progress = if (incoming.progress > 0) incoming.progress else existing.progress,
            summary = incoming.summary ?: existing.summary,
        )

        fun mergeSnapshotJson(existingJson: String?, incomingJson: String): String {
            if (existingJson == null) return incomingJson
            val existing = decodeOrNull(existingJson) ?: return incomingJson
            val incoming = decodeOrNull(incomingJson) ?: return incomingJson
            return mergeSnapshot(existing, incoming).toArchiveJson()
        }

        fun decodeOrNull(json: String): Archive? = runCatching {
            ArchiveLocalStateJson.decodeFromString(Archive.serializer(), json)
        }.getOrNull()
    }
}
