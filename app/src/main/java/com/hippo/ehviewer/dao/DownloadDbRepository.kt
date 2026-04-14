package com.hippo.ehviewer.dao

import androidx.room.withTransaction
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.flow.Flow

/**
 * Repository for download-related database operations, backed by [DownloadRoomDao].
 *
 * This is the final domain repository extracted from [com.hippo.ehviewer.EhDB] as part
 * of the incremental God Object decomposition. It is a thin delegation layer — no
 * business logic beyond what EhDB already had (profile filtering, state reset, label
 * deduplication, reorder logic).
 *
 * **Important distinction**: There is already a [com.hippo.ehviewer.download.DownloadRepository]
 * that manages IN-MEMORY download collections (lists, labels, infos). This class handles
 * the DATABASE persistence layer. These are separate concerns.
 *
 * Registered as a lazy val in [com.hippo.ehviewer.module.DataModule].
 */
class DownloadDbRepository(
    private val dao: DownloadRoomDao,
    private val database: AppDatabase
) {

    // ═══════════════════════════════════════════════════════════
    // DOWNLOAD INFO
    // ═══════════════════════════════════════════════════════════

    suspend fun getAllDownloadInfo(): List<DownloadInfo> {
        val profileId = LRRAuthManager.getActiveProfileId()
        val list = if (profileId > 0) dao.getDownloadInfoByServer(profileId) else dao.getAllDownloadInfo()
        for (info in list) {
            if (info.state == DownloadInfo.STATE_WAIT || info.state == DownloadInfo.STATE_DOWNLOAD) {
                info.state = DownloadInfo.STATE_NONE
            }
        }
        return list
    }

    /**
     * Returns a [Flow] that emits the current download list whenever the
     * DOWNLOADS table changes (insert/update/delete of persisted columns).
     *
     * Profile-aware: filters by the active server profile when one is set.
     *
     * **Important:** `@Ignore` fields (speed, downloaded, total, etc.) are NOT
     * persisted, so this Flow will NOT fire for progress-only changes.
     */
    fun observeDownloads(): Flow<List<DownloadInfo>> {
        val profileId = LRRAuthManager.getActiveProfileId()
        return if (profileId > 0)
            dao.observeDownloadsByServer(profileId)
        else
            dao.observeAllDownloads()
    }

    suspend fun moveDownloadInfo(infos: List<DownloadInfo>, fromPosition: Int, toPosition: Int) {
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
            dao.updateAll(list)
        }
    }

    suspend fun putDownloadInfo(downloadInfo: DownloadInfo) {
        dao.insert(downloadInfo)
    }

    suspend fun removeDownloadInfo(gid: Long) {
        dao.deleteDownloadByKey(gid)
    }

    suspend fun putDownloadInfoBatch(list: List<DownloadInfo>) {
        database.withTransaction {
            dao.insertAll(list)
        }
    }

    suspend fun removeDownloadInfoBatch(gids: List<Long>) {
        database.withTransaction {
            dao.deleteByGids(gids)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // DOWNLOAD DIRNAME
    // ═══════════════════════════════════════════════════════════

    suspend fun getDownloadDirname(gid: Long): String? {
        return dao.loadDirname(gid)?.dirname
    }

    suspend fun putDownloadDirname(gid: Long, dirname: String) {
        database.withTransaction {
            val raw = dao.loadDirname(gid)
            if (raw != null) {
                raw.dirname = dirname
                dao.updateDirname(raw)
            } else {
                val newRaw = DownloadDirname()
                newRaw.gid = gid
                newRaw.dirname = dirname
                dao.insertDirname(newRaw)
            }
        }
    }

    suspend fun removeDownloadDirname(gid: Long) {
        dao.deleteDirnameByKey(gid)
    }

    suspend fun clearDownloadDirname() {
        dao.deleteAllDirnames()
    }

    // ═══════════════════════════════════════════════════════════
    // DOWNLOAD LABELS
    // ═══════════════════════════════════════════════════════════

    suspend fun getAllDownloadLabels(): List<DownloadLabel> {
        return dao.getAllDownloadLabels()
    }

    suspend fun addDownloadLabel(label: String): DownloadLabel {
        val existing = dao.findLabelByName(label)
        if (existing != null) return existing
        val raw = DownloadLabel()
        raw.label = label
        raw.time = System.currentTimeMillis()
        raw.id = dao.insertLabel(raw)
        return raw
    }

    /**
     * Batch-insert multiple orphan label strings in a single transaction.
     *
     * Returns the list of [DownloadLabel] entities with their assigned IDs.
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
        val ids = database.withTransaction {
            dao.insertLabels(entities)
        }
        for (i in entities.indices) {
            entities[i].id = ids[i]
        }
        return entities
    }

    suspend fun addDownloadLabel(raw: DownloadLabel): DownloadLabel {
        raw.id = null
        raw.id = dao.insertLabel(raw)
        return raw
    }

    suspend fun updateDownloadLabel(raw: DownloadLabel) {
        dao.updateLabel(raw)
    }

    suspend fun moveDownloadLabel(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val reverse = fromPosition > toPosition
        val offset = if (reverse) toPosition else fromPosition
        val limit = if (reverse) fromPosition - toPosition + 1 else toPosition - fromPosition + 1
        val list = dao.getLabelsRange(offset, limit)
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
        dao.updateLabels(list)
    }

    suspend fun removeDownloadLabel(raw: DownloadLabel) {
        dao.deleteLabel(raw)
    }
}
