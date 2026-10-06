package com.lanraragi.reader.backup

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.room.withTransaction
import com.lanraragi.reader.backup.BackupMerge.Relink
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.DailyReadingAggregate
import com.lanraragi.reader.dao.DownloadDirname
import com.lanraragi.reader.dao.DownloadLabel
import com.lanraragi.reader.dao.QuickSearch
import com.lanraragi.reader.dao.SearchHistoryEntry
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.dao.TankDownloadGroup
import com.lanraragi.reader.gallery.LocalReadingProgress

/**
 * Merges a backup into this device's data (audit C05, ruling R18). Nothing local
 * is deleted; see [BackupMerge] for the per-field rules. Database writes are one
 * transaction; the two preference files are written after it commits. Restored
 * profiles have no API key and are never made active: the user picks a server
 * and enters its key as usual.
 *
 * @param currentRootUri the download root in use now, searched for re-linkable downloads
 */
class BackupImporter(
    private val db: AppDatabase,
    private val settings: SharedPreferences,
    private val readingProgress: SharedPreferences,
    private val relinker: DownloadRelinker,
    private val currentRootUri: () -> String?,
) {

    data class Result(
        val profilesAdded: Int = 0,
        val archivesRestored: Int = 0,
        val downloadsRelinked: Int = 0,
        val downloadsSkipped: Int = 0,
        val settingsApplied: Int = 0,
    )

    suspend fun restore(backup: LrrBackup): Result {
        // Disk scan first, outside the transaction.
        val roots = listOfNotNull(currentRootUri()) + backup.archives.mapNotNull { it.downloadRootUri }
        val onDisk = relinker.index(roots)
        val (result, ids) = db.withTransaction {
            val (ids, added) = restoreProfiles(backup.profiles)
            val archives = restoreArchives(backup.archives, ids, onDisk)
            restoreLabels(backup.downloadLabels)
            restoreTankGroups(backup.tankGroups, ids)
            restoreQuickSearches(backup.quickSearches)
            restoreSearchHistory(backup.searchHistory, ids)
            restoreAggregates(backup.dailyAggregates, ids)
            archives.copy(profilesAdded = added) to ids
        }
        // Entries from backups made before progress was kept per server belong to its active one.
        val legacyOwner = backup.profiles.firstOrNull { it.isActive }?.id?.let { ids[it] }
        mergeReadingProgress(backup.readingProgress, ids, legacyOwner)
        return result.copy(settingsApplied = BackupSettings.apply(settings, backup.settings))
    }

    /** @return backup profile id → local id, and how many profiles were added */
    private suspend fun restoreProfiles(profiles: List<BackupProfile>): Pair<Map<Long, Long>, Int> {
        val misc = db.miscDao()
        val ids = HashMap(BackupMerge.matchProfiles(misc.getAllServerProfiles(), profiles))
        val missing = profiles.filter { it.id !in ids }
        for (p in missing) {
            ids[p.id] = misc.insertServerProfile(
                ServerProfile(name = p.name, url = p.url, isActive = false, allowCleartext = p.allowCleartext)
            )
        }
        return ids to missing.size
    }

    private suspend fun restoreArchives(
        rows: List<BackupArchive>,
        ids: Map<Long, Long>,
        onDisk: Map<String, Relink>,
    ): Result {
        val archives = db.archiveLocalStateDao()
        var restored = 0
        var relinked = 0
        var skipped = 0
        for (row in rows.mapNotNull { b -> ids[b.profileId]?.let { b.copy(profileId = it) } }) {
            val local = archives.loadByArcidAndProfile(row.arcid, row.profileId)
            val localDownload = local?.downloadState != null
            // One download row per arcid (C45): a download kept under another profile wins.
            val relink = onDisk[row.arcid]?.takeIf { localDownload || !archives.hasDownloadRow(row.arcid) }
            val merged = BackupMerge.mergeArchive(local, row, relink)
            if (merged != null) {
                if (local == null) archives.insertNew(merged) else archives.update(merged)
                restored++
            }
            if (!localDownload && row.downloadState != null) {
                if (merged?.downloadState != null && relink != null) {
                    relinked++
                    db.downloadDao().insertDirname(DownloadDirname().apply { arcid = row.arcid; dirname = relink.dirname })
                } else {
                    skipped++
                }
            }
        }
        return Result(archivesRestored = restored, downloadsRelinked = relinked, downloadsSkipped = skipped)
    }

    private suspend fun restoreLabels(labels: List<BackupLabel>) {
        val downloads = db.downloadDao()
        for (l in labels.filter { downloads.findLabelByName(it.label) == null }) {
            downloads.insertLabel(DownloadLabel().apply { label = l.label; time = l.time })
        }
    }

    /** A tank group comes back when one of its members is a download on this device. */
    private suspend fun restoreTankGroups(groups: List<BackupTankGroup>, ids: Map<Long, Long>) {
        val dao = db.tankDownloadGroupDao()
        val archives = db.archiveLocalStateDao()
        for (g in groups) {
            val profileId = ids[g.profileId]
            val wanted = profileId != null && dao.getById(g.tankId) == null &&
                archives.getDownloadsByTank(g.tankId).isNotEmpty()
            if (wanted && profileId != null) {
                dao.upsert(TankDownloadGroup(g.tankId, profileId, g.name, g.memberIdsJson, g.createdTime))
            }
        }
    }

    private suspend fun restoreQuickSearches(searches: List<BackupQuickSearch>) {
        val browsing = db.browsingDao()
        val local = browsing.getAllQuickSearch()
        for (q in searches.filter { b -> local.none { BackupMerge.sameQuickSearch(it.name, it.keyword, b) } }) {
            browsing.insertQuickSearch(
                QuickSearch().apply {
                    name = q.name; mode = q.mode; category = q.category; keyword = q.keyword
                    categoryId = q.categoryId; categoryName = q.categoryName; advanceSearch = q.advanceSearch
                    minRating = q.minRating; pageFrom = q.pageFrom; pageTo = q.pageTo; time = q.time
                }
            )
        }
    }

    private suspend fun restoreSearchHistory(entries: List<BackupSearchHistory>, ids: Map<Long, Long>) {
        val browsing = db.browsingDao()
        val local = browsing.getAllSearchHistory().associateBy { it.serverProfileId to it.query }
        for (h in entries) {
            val profileId = ids[h.profileId]
            val existing = profileId?.let { local[it to h.query] }
            if (profileId != null && (existing == null || h.lastUsed > existing.lastUsed)) {
                browsing.upsertSearchHistory(
                    SearchHistoryEntry().apply { query = h.query; serverProfileId = profileId; lastUsed = h.lastUsed }
                )
            }
        }
    }

    private suspend fun restoreAggregates(rows: List<BackupDailyAggregate>, ids: Map<Long, Long>) {
        val stats = db.statsDao()
        val local = stats.getAllDailyAggregates().associateBy { it.epochDay to it.serverProfileId }
        for (a in rows.mapNotNull { b -> ids[b.profileId]?.let { b.copy(profileId = it) } }) {
            val existing = local[a.epochDay to a.profileId]
            if (existing == null) {
                stats.insertDailyAggregateIfAbsent(
                    DailyReadingAggregate().apply {
                        epochDay = a.epochDay; serverProfileId = a.profileId; pagesRead = a.pagesRead; completed = a.completed
                    }
                )
            } else {
                val (pages, completed) = BackupMerge.mergeAggregate(existing.pagesRead, existing.completed, a)
                stats.accumulateDailyAggregate(
                    a.epochDay, a.profileId, pages - existing.pagesRead, completed - existing.completed,
                )
            }
        }
    }

    /** Keys follow [LocalReadingProgress]: `<local profile id>:<arcid>`. */
    private fun mergeReadingProgress(entries: List<BackupReadingProgress>, ids: Map<Long, Long>, legacyOwner: Long?) {
        val all = readingProgress.all
        readingProgress.edit {
            for (e in entries) {
                val owner = if (e.profileId == null) legacyOwner else ids[e.profileId]
                val key = owner?.let { LocalReadingProgress.key(it, e.arcid) }
                val localTs = key?.let { all[it + BackupExporter.TS_SUFFIX] as? Long } ?: 0L
                if (key != null && BackupMerge.backupProgressWins(localTs, key in all, e)) {
                    putInt(key, e.page)
                    putLong(key + BackupExporter.TS_SUFFIX, e.savedAt)
                }
            }
        }
    }
}
