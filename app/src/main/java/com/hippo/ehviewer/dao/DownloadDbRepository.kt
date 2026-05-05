package com.hippo.ehviewer.dao

import androidx.room.withTransaction
import com.hippo.ehviewer.download.DownloadState
import com.hippo.ehviewer.mapper.toArchive
import com.hippo.ehviewer.mapper.toArchiveJson
import com.hippo.ehviewer.mapper.toDownloadInfoView
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for download-related database operations.
 *
 * Post-L1 the `DOWNLOADS` table is gone — download rows live on
 * `ARCHIVE_LOCAL_STATE` keyed off `DOWNLOAD_STATE IS NOT NULL`. The
 * label and dirname tables are unchanged and continue to flow through
 * [DownloadRoomDao].
 *
 * **Atomicity strategy**: the per-archive download mutations
 * (`putDownloadInfo`, `removeDownloadInfoByArcid`, `updateRating`) use
 * the INSERT-OR-IGNORE-then-UPDATE pattern. Each statement is its own
 * atomic SQL op; the pair preserves cross-subsystem columns
 * (history / favorite) without putting the calling coroutine into
 * Room's transaction dispatcher — that would otherwise deadlock the
 * invalidation observer that drives [observeDownloads] in unit tests
 * with inline executors.
 *
 * The only operation that *does* take a `withTransaction` lock is
 * [moveDownloadInfo], because per-row UPDATEs there are not safe to
 * interleave with concurrent writes on the same arcids. The other
 * batch operations are sequenced loops over the atomic mutations.
 *
 * **Important distinction**: there is also a
 * [com.hippo.ehviewer.download.DownloadRepository] that manages
 * IN-MEMORY download collections (lists, labels, infos). This class
 * handles the DATABASE persistence layer. Two separate concerns.
 *
 * Registered as a lazy val in [com.hippo.ehviewer.module.DataModule].
 */
class DownloadDbRepository(
    private val archiveLocalStateDao: ArchiveLocalStateDao,
    private val downloadDao: DownloadRoomDao,
    private val database: AppDatabase,
) {

    // ═══════════════════════════════════════════════════════════
    // DOWNLOAD INFO
    // ═══════════════════════════════════════════════════════════

    suspend fun getAllDownloadInfo(): List<DownloadInfo> {
        val rows = archiveLocalStateDao.getAllDownloads()
        val list = rows.map { it.toDownloadInfoView() }
        for (info in list) {
            // Reset transient WAIT/DOWNLOAD states (process restart →
            // these aren't real anymore). Mirrors v22 behavior.
            if (info.state == DownloadState.WAIT || info.state == DownloadState.DOWNLOAD) {
                info.state = DownloadState.NONE
            }
        }
        return list
    }

    /**
     * Returns a [Flow] that emits the current download list whenever
     * the persisted download fields change. The adapter to memory
     * views is applied on each emission.
     *
     * **Profile-agnostic**: returns downloads from every configured
     * profile. Cross-profile UX (badge on `serverProfileId != active`,
     * orphan handling, per-archive resume) treats the list as a global
     * inventory of "what's on disk", with profile membership rendered
     * per-row. Filtering by `getActiveProfileId()` here would race
     * against profile switching — the captured id is stale by the time
     * the user navigates back to the downloads list — and surfaced as
     * the "empty list after switch" symptom.
     */
    fun observeDownloads(): Flow<List<DownloadInfo>> {
        return archiveLocalStateDao.observeAllDownloads()
            .map { rows -> rows.map { it.toDownloadInfoView() } }
    }

    /**
     * Reorder a contiguous slice of the download list by rotating
     * DOWNLOAD_TIME values. Mirrors the v22 swap algorithm; only the
     * persistence step is different (per-row UPDATE on the unified
     * table instead of `dao.updateAll`).
     *
     * This is the one place that benefits from a transaction — the
     * per-row UPDATEs must succeed or fail as a unit so that the
     * resulting ordering is consistent.
     */
    suspend fun moveDownloadInfo(
        infos: List<DownloadInfo>,
        fromPosition: Int,
        toPosition: Int,
    ) {
        if (fromPosition == toPosition) return
        database.withTransaction {
            val reverse = fromPosition > toPosition
            val offset = if (reverse) toPosition else fromPosition
            val limit = if (reverse) fromPosition - toPosition + 1 else toPosition - fromPosition + 1
            val list = infos.subList(offset, offset + limit)
            val step = if (reverse) 1 else -1
            val start = if (reverse) limit - 1 else 0
            val end = if (reverse) 0 else limit - 1
            val toTime = list[end].time
            var i = end
            while (if (reverse) i < start else i > start) {
                list[i].time = list[i + step].time
                i += step
            }
            list[start].time = toTime
            for (info in list) {
                archiveLocalStateDao.updateDownloadTime(info.arcid, info.time)
            }
        }
    }

    suspend fun putDownloadInfo(downloadInfo: DownloadInfo) {
        upsertDownloadSubsystem(downloadInfo)
    }

    /**
     * Update the rating for a download identified by [arcid]. The
     * rating lives in `archive_json`, so the in-row update has to
     * load, patch, and rewrite the JSON column.
     */
    suspend fun updateRating(arcid: String, rating: Float) {
        val row = archiveLocalStateDao.loadByArcid(arcid) ?: return
        val archive = ArchiveLocalStateJson.decodeFromString(Archive.serializer(), row.archiveJson)
        archiveLocalStateDao.updateArchiveJson(arcid, archive.copy(rating = rating).toArchiveJson())
    }

    suspend fun removeDownloadInfoByArcid(arcid: String) {
        archiveLocalStateDao.clearDownloadSubsystem(arcid)
        archiveLocalStateDao.deleteIfNoSubsystem(arcid)
    }

    suspend fun putDownloadInfoBatch(list: List<DownloadInfo>) {
        if (list.isEmpty()) return
        for (info in list) {
            upsertDownloadSubsystem(info)
        }
    }

    suspend fun removeDownloadInfoBatchByArcids(arcids: List<String>) {
        if (arcids.isEmpty()) return
        for (arcid in arcids) {
            archiveLocalStateDao.clearDownloadSubsystem(arcid)
            archiveLocalStateDao.deleteIfNoSubsystem(arcid)
        }
    }

    /**
     * Idempotent INSERT-OR-IGNORE-then-UPDATE for the download
     * subsystem. INSERT seeds a new row only if absent; UPDATE writes
     * the download columns regardless. Cross-subsystem columns
     * (history / favorite) on a pre-existing row stay untouched.
     */
    private suspend fun upsertDownloadSubsystem(downloadInfo: DownloadInfo) {
        val archiveJson = downloadInfo.toArchive().toArchiveJson()
        archiveLocalStateDao.insertOrIgnoreDownload(
            arcid = downloadInfo.arcid,
            serverProfileId = downloadInfo.serverProfileId,
            archiveJson = archiveJson,
            downloadState = downloadInfo.state,
            downloadLegacy = downloadInfo.legacy,
            downloadTime = downloadInfo.time,
            downloadLabel = downloadInfo.label,
            downloadArchiveUri = downloadInfo.archiveUri,
        )
        archiveLocalStateDao.updateDownloadFields(
            arcid = downloadInfo.arcid,
            serverProfileId = downloadInfo.serverProfileId,
            archiveJson = archiveJson,
            downloadState = downloadInfo.state,
            downloadLegacy = downloadInfo.legacy,
            downloadTime = downloadInfo.time,
            downloadLabel = downloadInfo.label,
            downloadArchiveUri = downloadInfo.archiveUri,
        )
    }

    // ═══════════════════════════════════════════════════════════
    // DOWNLOAD DIRNAME (separate table, not touched by L1)
    // ═══════════════════════════════════════════════════════════

    suspend fun getDownloadDirname(arcid: String): String? {
        return downloadDao.loadDirname(arcid)?.dirname
    }

    suspend fun putDownloadDirname(arcid: String, dirname: String) {
        val raw = downloadDao.loadDirname(arcid)
        if (raw != null) {
            raw.dirname = dirname
            downloadDao.updateDirname(raw)
        } else {
            val newRaw = DownloadDirname(arcid = arcid, dirname = dirname)
            downloadDao.insertDirname(newRaw)
        }
    }

    suspend fun removeDownloadDirname(arcid: String) {
        downloadDao.deleteDirnameByKey(arcid)
    }

    suspend fun clearDownloadDirname() {
        downloadDao.deleteAllDirnames()
    }

    // ═══════════════════════════════════════════════════════════
    // DOWNLOAD LABELS (separate table, not touched by L1)
    // ═══════════════════════════════════════════════════════════

    suspend fun getAllDownloadLabels(): List<DownloadLabel> {
        return downloadDao.getAllDownloadLabels()
    }

    suspend fun addDownloadLabel(label: String): DownloadLabel {
        val existing = downloadDao.findLabelByName(label)
        if (existing != null) return existing
        val raw = DownloadLabel()
        raw.label = label
        raw.time = System.currentTimeMillis()
        raw.id = downloadDao.insertLabel(raw)
        return raw
    }

    /**
     * Batch-insert multiple orphan label strings. Returns the list of
     * [DownloadLabel] entities with their assigned IDs.
     */
    suspend fun addDownloadLabels(labels: List<String>): List<DownloadLabel> {
        if (labels.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val entities = labels.mapIndexed { index, label ->
            DownloadLabel().apply {
                this.label = label
                this.time = now + index
            }
        }
        val ids = downloadDao.insertLabels(entities)
        for (i in entities.indices) {
            entities[i].id = ids[i]
        }
        return entities
    }

    suspend fun addDownloadLabel(raw: DownloadLabel): DownloadLabel {
        raw.id = null
        raw.id = downloadDao.insertLabel(raw)
        return raw
    }

    suspend fun updateDownloadLabel(raw: DownloadLabel) {
        downloadDao.updateLabel(raw)
    }

    suspend fun moveDownloadLabel(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val reverse = fromPosition > toPosition
        val offset = if (reverse) toPosition else fromPosition
        val limit = if (reverse) fromPosition - toPosition + 1 else toPosition - fromPosition + 1
        val list = downloadDao.getLabelsRange(offset, limit)
        val step = if (reverse) 1 else -1
        val start = if (reverse) limit - 1 else 0
        val end = if (reverse) 0 else limit - 1
        val toTime = list[end].time
        var i = end
        while (if (reverse) i < start else i > start) {
            list[i].time = list[i + step].time
            i += step
        }
        list[start].time = toTime
        downloadDao.updateLabels(list)
    }

    suspend fun removeDownloadLabel(raw: DownloadLabel) {
        downloadDao.deleteLabel(raw)
    }
}
