package com.hippo.ehviewer.updater

import android.content.Context
import java.io.File

/**
 * Sealed APK download progress. The download Flow emits [InProgress] (throttled to ~256 KB
 * or first read), then terminates with [Success] or [Failed]. Caller cancellation is honored
 * — the in-flight OkHttp call is cancelled and the partial file is deleted inside `awaitClose`.
 *
 * The actual download Flow lives in [ApkDownloader.download]; this file currently defines the
 * data types + the file-path helper. Task 2 adds [ApkDownloader.download].
 */
sealed class DownloadProgress {
    data class InProgress(val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress() {
        /**
         * 0-100 (or 0 if totalBytes unknown / zero).
         *
         * Note: may exceed 100 if the server's Content-Length is stale; UI callers
         * should `coerceIn(0, 100)` before display (LinearProgressIndicator clips
         * visually but a `"$percent%"` text label would not).
         */
        val percent: Int get() = if (totalBytes > 0) (100 * bytesDownloaded / totalBytes).toInt() else 0
    }
    object Success : DownloadProgress()
    data class Failed(val cause: Throwable) : DownloadProgress()
}

/**
 * APK downloader. Pure IO concern — no Activity, Toast, R.string, or permission logic.
 * UpdateDialog drives the Flow and handles all UI + install intent.
 */
object ApkDownloader {

    /**
     * Compute the target [File] for downloading [release]'s APK asset into
     * `context.cacheDir/updates/<asset-name>`. Creates the `updates/` subdir if missing.
     * Returns null if [release] has no APK asset or if the directory cannot be created.
     */
    fun targetFile(context: Context, release: GhRelease): File? {
        val name = release.apkAsset?.name ?: return null
        val dir = File(context.cacheDir, "updates")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return File(dir, name)
    }
}
