package com.lanraragi.reader.download

import android.util.Log
import com.lanraragi.reader.dao.DownloadDbRepository

/**
 * One-shot boot repair for `DOWNLOAD_DIRNAME` pointers that name the same
 * directory. Before pointer allocation became atomic (DownloadDirAllocator),
 * read paths could mint one name for two same-titled archives.
 *
 * Per shared name (case-insensitive) it keeps the finished download's
 * pointer and clears the others, so their next download allocates a
 * directory of their own. With no finished download in the group it
 * keeps the first tracked one. Two or more finished downloads sharing a
 * directory cannot be told apart from the files, so they are only logged.
 */
class DuplicateDirPointerRepair(private val repo: DownloadDbRepository) {

    data class Outcome(val cleared: Int, val unresolved: Int)

    suspend fun run(): Outcome {
        var cleared = 0
        var unresolved = 0
        val groups = repo.getAllDownloadDirnamePointers().entries
            .groupBy({ it.value.lowercase() }, { it.key })
            .values
            .filter { it.size > 1 }
        for (arcids in groups) {
            val states = arcids.sorted().associateWith { repo.getDownloadState(it) }
            val finished = states.filterValues { it == DownloadState.FINISH }.keys
            val keep = finished.ifEmpty { states.filterValues { it != null }.keys.take(1).toSet() }
            if (finished.size > 1) unresolved++
            for (arcid in states.keys - keep) {
                repo.removeDownloadDirname(arcid)
                cleared++
            }
        }
        if (unresolved > 0) {
            Log.w(TAG, "Some download directories are shared by several finished downloads")
        }
        return Outcome(cleared, unresolved)
    }

    companion object {
        private const val TAG = "DuplicateDirPointerRepair"

        /** `boot_cleanup` pref key set once the repair has run. */
        const val PREF_DONE = "download_dir_duplicate_pointer_repaired"
    }
}
