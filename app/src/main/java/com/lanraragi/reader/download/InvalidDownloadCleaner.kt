package com.lanraragi.reader.download

import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.settings.DownloadSettings
import com.lanraragi.reader.storage.StorageFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Clean invalid downloads" (download settings): deletes the empty
 * directories in the download location and writes a `delfile-*.log` of
 * what it removed into that location.
 *
 * [start] runs the scan on the process scope, so neither a rotation nor
 * closing the settings screen cancels it or loses its result.
 *
 * @param scope the long-lived scope the scan runs on; null = the app's IO
 *   scope, resolved at [start]
 */
class InvalidDownloadCleaner(private val scope: CoroutineScope? = null) {

    /** Scan progress. Called on the scan's (background) thread. */
    interface Listener {
        /** The location holds [total] entries to check. */
        fun onTotal(total: Int)

        /** [checked] of the entries are checked. */
        fun onProgress(checked: Int)

        /** Done; [removed] empty directories were deleted (0 also when there is no location). */
        fun onDone(removed: Int)
    }

    /** Cleans the configured download location in the background. */
    fun start(listener: Listener): Job = (scope ?: ServiceRegistry.coroutineModule.ioScope).launch {
        listener.onDone(clean(DownloadSettings.getDownloadLocation(), listener))
    }

    /** Cleans [downloadDir] on the calling thread; returns how many directories were removed. */
    fun clean(downloadDir: StorageFile?, listener: Listener): Int {
        val files = downloadDir?.takeIf { it.isDirectory }?.listFiles()
        if (downloadDir == null || files == null) return 0

        listener.onTotal(files.size)
        val logs = mutableListOf<String>()
        for (i in files.indices) {
            val dir = files[i]
            listener.onProgress(i + 1)
            if (!dir.isDirectory) continue
            val subFiles = dir.listFiles()
            if (subFiles == null || subFiles.isEmpty()) {
                logs.add("Empty directory: ${dir.name}")
                dir.delete()
            }
        }
        if (logs.isNotEmpty()) saveCleanLog(downloadDir, logs)
        return logs.size
    }

    private fun saveCleanLog(downloadDir: StorageFile, logs: List<String>) {
        val sdf = SimpleDateFormat("yyyyMMddHHmm", Locale.US)
        val logFile = downloadDir.createFile("delfile-${sdf.format(Date())}.log") ?: return
        try {
            logFile.openOutputStream().use { os ->
                for (log in logs) {
                    os.write("$log\n".toByteArray(StandardCharsets.UTF_8))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Save clean log file", e)
        }
    }

    companion object {
        private const val TAG = "InvalidDownloadCleaner"

        val default: InvalidDownloadCleaner by lazy { InvalidDownloadCleaner() }
    }
}
