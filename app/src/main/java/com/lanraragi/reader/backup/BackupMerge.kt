package com.lanraragi.reader.backup

import com.lanraragi.reader.dao.ArchiveLocalState
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.download.DownloadState

/**
 * Merge rules for a restore (ruling R18: merge, never delete local data).
 * Pure functions so every rule is unit-tested without a database.
 */
object BackupMerge {

    /** Profiles are the same server when their URLs match without a trailing slash. */
    fun sameServer(a: String, b: String): Boolean = a.trim().removeSuffix("/") == b.trim().removeSuffix("/")

    /**
     * Backup profile id → local profile id for servers already on the device;
     * backup profiles missing from the map must be inserted.
     */
    fun matchProfiles(local: List<ServerProfile>, backup: List<BackupProfile>): Map<Long, Long> =
        backup.mapNotNull { b -> local.firstOrNull { sameServer(it.url, b.url) }?.let { b.id to it.id } }.toMap()

    /** Where a backed-up download was found on disk. */
    data class Relink(val rootUri: String, val dirname: String)

    /**
     * One archive row after the merge, or null when nothing of it survives.
     * - history: the side with the later history time wins (with its snapshot, mode and scroll position);
     * - favourite: the earlier favourite time (it is when the user first starred it);
     * - download: a local download is kept; otherwise the backup's comes back only when [relink] found
     *   its files (a finished download stays finished, anything else restarts from NONE).
     *
     * @param backup already carrying the local profile id
     */
    fun mergeArchive(local: ArchiveLocalState?, backup: BackupArchive, relink: Relink?): ArchiveLocalState? {
        val backupHistoryWins = backup.historyTime != null &&
            (local?.historyTime == null || backup.historyTime > local.historyTime)
        val favorite = listOfNotNull(local?.favoriteTime, backup.favoriteTime).minOrNull()
        val base = local ?: ArchiveLocalState(
            arcid = backup.arcid,
            serverProfileId = backup.profileId,
            archiveJson = backup.archiveJson,
        )
        var merged = base.copy(favoriteTime = favorite)
        if (backupHistoryWins) {
            merged = merged.copy(
                archiveJson = backup.archiveJson,
                historyTime = backup.historyTime,
                historyMode = backup.historyMode,
                historyScrollFraction = backup.historyScrollFraction,
            )
        }
        if (local?.downloadState == null) {
            merged = if (backup.downloadState != null && relink != null) {
                merged.copy(
                    downloadState = restoredState(backup.downloadState),
                    downloadLegacy = backup.downloadLegacy,
                    downloadTime = backup.downloadTime,
                    downloadLabel = backup.downloadLabel,
                    downloadArchiveUri = backup.downloadArchiveUri,
                    downloadRootUri = relink.rootUri,
                    downloadTankId = backup.downloadTankId,
                )
            } else {
                merged.copy(
                    downloadState = null, downloadLegacy = 0, downloadTime = null, downloadLabel = null,
                    downloadArchiveUri = null, downloadRootUri = null, downloadTankId = null,
                )
            }
        }
        val empty = merged.historyTime == null && merged.favoriteTime == null && merged.downloadState == null
        return if (empty) null else merged
    }

    private fun restoredState(code: Int): DownloadState =
        if (DownloadState.fromCode(code) == DownloadState.FINISH) DownloadState.FINISH else DownloadState.NONE

    /** Per (day, profile) the larger counts: the same reading must not be counted twice. */
    fun mergeAggregate(localPages: Long, localCompleted: Int, backup: BackupDailyAggregate): Pair<Long, Int> =
        maxOf(localPages, backup.pagesRead) to maxOf(localCompleted, backup.completed)

    /** True when the backed-up progress is newer than the local save (saved at [localSavedAt], 0 = none). */
    fun backupProgressWins(localSavedAt: Long, hasLocal: Boolean, backup: BackupReadingProgress): Boolean =
        !hasLocal || backup.savedAt > localSavedAt

    /** A quick search is a duplicate when name and keyword match. */
    fun sameQuickSearch(name: String?, keyword: String?, backup: BackupQuickSearch): Boolean =
        name == backup.name && keyword == backup.keyword
}
