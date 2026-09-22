package com.lanraragi.reader.gallery

import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import kotlinx.coroutines.launch

/**
 * Writes where a reading session ended into the archive's history snapshot
 * (`archive_json` progress / lastreadtime), the same pair the server holds
 * after our progress PUT. Without it the snapshot kept the progress from
 * when the reader was opened, so the continue-reading widget and the
 * reading statistics showed stale positions — permanently for offline and
 * download-only reading, where no metadata refresh ever corrects it.
 */
object ReadingSnapshotRecorder : ReadingSessionEvents.Listener {

    private const val TAG = "ReadingSnapshotRecorder"

    /** Register on the session-end seam. Call once from Application.onCreate. */
    fun install() {
        ReadingSessionEvents.register(this)
    }

    override fun onSessionEnd(end: ReadingSessionEnd) {
        if (end.serverProfileId <= 0 || end.endPage < 0) return
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                ServiceRegistry.dataModule.historyRepository
                    .recordSessionProgress(end.arcid, end.serverProfileId, end.endPage + 1)
            } catch (e: Exception) {
                Log.w(TAG, "history snapshot progress write failed")
            }
        }
    }
}
