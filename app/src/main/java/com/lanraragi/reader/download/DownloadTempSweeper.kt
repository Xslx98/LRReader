package com.lanraragi.reader.download

import java.io.File

/**
 * Removes `*.tmp` leftovers from a download directory (audit 2026-10-04
 * C47). Page writes go through a temp file and a rename; a process killed
 * mid-page leaves the temp file behind, and nothing deleted it before.
 *
 * Only files older than [STALE_AFTER_MS] are removed: the reader's hybrid
 * mode writes its own temp files into the same directory, and one of those
 * may be in flight while a worker starts.
 */
internal object DownloadTempSweeper {
    const val STALE_AFTER_MS = 10 * 60 * 1000L

    /** @return how many temp files were deleted. */
    fun sweep(dir: File, nowMillis: Long = System.currentTimeMillis()): Int =
        dir.listFiles().orEmpty().count { f ->
            f.isFile && f.name.endsWith(".tmp") && nowMillis - f.lastModified() > STALE_AFTER_MS && f.delete()
        }
}
