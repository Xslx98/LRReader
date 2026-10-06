package com.lanraragi.reader.download

import android.content.Context
import android.content.Intent
import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * A reader's hand-over of a damaged download-directory page to the download
 * pipeline (audit 2026-10-06e P4-d).
 *
 * Only the download pipeline deletes or replaces a page in a download
 * directory (audit 2026-10-06d PERF-01). Its resume check trusts a valid
 * header and a plausible size, so a page whose header is fine but whose body
 * is damaged would be skipped for ever. When a reader finds such a page (the
 * decoder rejects a format this device can decode), it leaves the file alone
 * and drops a `<page>.refetch` marker next to it; [LRRDownloadWorker] then
 * treats the page as missing, fetches it again (tmp + rename over the bad
 * file) and removes the marker. A download that has already finished is
 * re-queued for that ([requeueIfFinished]); every other page is on disk and
 * skipped, so only the marked page is downloaded again.
 */
object DownloadPageRepair {

    private const val TAG = "DownloadPageRepair"

    /** Suffix of a repair marker; not an image extension, so page listings ignore it. */
    const val MARKER_SUFFIX = ".refetch"

    fun markerFor(pageFile: File): File = File(pageFile.parentFile, pageFile.name + MARKER_SUFFIX)

    fun isMarker(file: File): Boolean = file.name.endsWith(MARKER_SUFFIX)

    /** Asks for [pageFile] to be fetched again; true when the marker is on disk. */
    fun mark(pageFile: File): Boolean {
        val marker = markerFor(pageFile)
        return try {
            marker.createNewFile() || marker.exists()
        } catch (e: IOException) {
            Log.e(TAG, "Could not mark a damaged page for re-download", e)
            false
        }
    }

    fun isMarked(pageFile: File): Boolean = markerFor(pageFile).exists()

    /** The page was fetched again: drop its marker. */
    fun clear(pageFile: File) {
        val marker = markerFor(pageFile)
        if (marker.exists() && !marker.delete()) Log.w(TAG, "Could not remove a page repair marker")
    }

    /** Whether any page of [dir] waits for a re-download. */
    fun hasPending(dir: File): Boolean =
        dir.list()?.any { it.endsWith(MARKER_SUFFIX) } == true

    /**
     * Re-queue [arcid]'s download when it has already finished, so the
     * worker re-fetches the marked page. A queued or running download picks
     * the marker up by itself; a paused, failed or deleted one is left as
     * the user left it (the marker waits for the next start). Main thread
     * for the download-state lookup, posted from any thread.
     */
    fun requeueIfFinished(arcid: String) {
        ServiceRegistry.coroutineModule.applicationScope.launch {
            val context = ServiceRegistry.appModule.getContext()
            requeueIfFinished(arcid, ServiceRegistry.dataModule.downloadManager::getDownloadState) { id ->
                startRange(context, id)
            }
        }
    }

    /** [requeueIfFinished] with its lookups injected; true when a re-queue was asked for. */
    internal fun requeueIfFinished(
        arcid: String,
        stateOf: (String) -> DownloadState,
        restart: (String) -> Unit,
    ): Boolean {
        if (stateOf(arcid) != DownloadState.FINISH) return false
        restart(arcid)
        return true
    }

    private fun startRange(context: Context, arcid: String) {
        val intent = Intent(context, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START_RANGE
        intent.putStringArrayListExtra(DownloadService.KEY_ARCID_LIST, arrayListOf(arcid))
        context.startService(intent)
    }
}
