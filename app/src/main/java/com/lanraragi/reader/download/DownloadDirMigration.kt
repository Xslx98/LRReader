package com.lanraragi.reader.download

import android.util.Log
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.dao.DownloadDbRepository
import com.lanraragi.reader.dao.DownloadInfo

/**
 * One-shot boot migration from `<arcid>-<title>` download directories to
 * the title-only rule in [DownloadDirNaming]. Runs row by row — each row
 * is "rename on disk, then repoint the DB" — and is re-entrant: anything
 * it could not finish (an active download, a failed rename) is left as is
 * and retried on the next boot; the caller sets its pref guard only when
 * [Outcome.complete] is true.
 *
 * Rows whose pointer names a directory that no longer exists lose the
 * pointer, so the next use allocates a title-style name instead of
 * recreating the legacy one.
 */
class DownloadDirMigration(
    private val repo: DownloadDbRepository,
    private val resolveRoot: (storedUri: String?) -> UniFile?,
) {

    enum class RowResult { RENAMED, ALREADY_NEW, NO_POINTER, POINTER_CLEARED, SKIPPED_ACTIVE, FAILED }

    data class Outcome(val results: Map<RowResult, Int>) {
        /** Nothing left for a later boot to do. */
        val complete: Boolean
            get() = (results[RowResult.SKIPPED_ACTIVE] ?: 0) == 0 && (results[RowResult.FAILED] ?: 0) == 0
    }

    suspend fun run(): Outcome {
        val counts = mutableMapOf<RowResult, Int>()
        for (info in repo.getAllDownloadInfo()) {
            val result = runCatching { migrateRow(info) }.getOrElse { t ->
                Log.w(TAG, "Download dir migration failed for ${info.arcid}: ${t.message}")
                RowResult.FAILED
            }
            counts[result] = (counts[result] ?: 0) + 1
        }
        return Outcome(counts)
    }

    suspend fun migrateRow(info: DownloadInfo): RowResult {
        val arcid = info.arcid
        val dirname = repo.getDownloadDirname(arcid) ?: return RowResult.NO_POINTER
        if (!DownloadDirNaming.isLegacyName(arcid, dirname)) return RowResult.ALREADY_NEW
        // A worker may be writing into this directory right now (the user
        // restarted a download since boot); renaming under it would orphan
        // its pages. Leave the row for the next boot.
        val state = repo.getDownloadState(arcid)
        if (state == DownloadState.WAIT || state == DownloadState.DOWNLOAD) return RowResult.SKIPPED_ACTIVE
        val root = resolveRoot(info.downloadRootUri) ?: return RowResult.FAILED
        val dir = root.findFile(dirname)
        if (dir == null || !dir.isDirectory) {
            repo.removeDownloadDirname(arcid)
            return RowResult.POINTER_CLEARED
        }
        val newName = DownloadDirNaming.uniqueName(DownloadDirNaming.baseName(arcid, info.title)) { name ->
            root.findFile(name) != null
        }
        if (!dir.renameTo(newName)) return RowResult.FAILED
        repo.putDownloadDirname(arcid, newName)
        return RowResult.RENAMED
    }

    companion object {
        private const val TAG = "DownloadDirMigration"

        /** `boot_cleanup` pref key set once every row has been migrated. */
        const val PREF_DONE = "download_dir_title_naming_migrated"
    }
}
