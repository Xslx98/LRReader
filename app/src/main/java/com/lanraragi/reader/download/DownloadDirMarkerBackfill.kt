package com.lanraragi.reader.download

import android.util.Log
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.dao.DownloadDbRepository
import com.lanraragi.reader.util.suspendRunCatching

/**
 * One-shot boot pass that writes a [DownloadDirMarker] into the directory of
 * every download that predates the marker. Re-entrant: a row whose directory
 * exists but could not be written is retried on the next boot; the caller
 * sets [PREF_DONE] only when [run] returns true. Rows without a directory
 * (not started yet, or files deleted) are skipped — the worker writes the
 * marker when it creates the directory.
 */
class DownloadDirMarkerBackfill(
    private val repo: DownloadDbRepository,
    private val findDir: suspend (arcid: String, rootUri: String?) -> UniFile?,
) {

    /** @return true when every existing download directory holds a marker. */
    suspend fun run(): Boolean {
        var complete = true
        for (info in repo.getAllDownloadInfo()) {
            val written = suspendRunCatching {
                val dir = findDir(info.arcid, info.downloadRootUri)
                dir == null || !dir.isDirectory || DownloadDirMarker.write(dir, DownloadDirMarker.of(info))
            }.getOrElse { t ->
                Log.w(TAG, "Marker backfill failed for ${info.arcid}: ${t.message}")
                false
            }
            if (!written) complete = false
        }
        return complete
    }

    companion object {
        private const val TAG = "DownloadDirMarkerBackfill"

        /** `boot_cleanup` pref key set once every download directory has a marker. */
        const val PREF_DONE = "download_dir_marker_backfilled"
    }
}
