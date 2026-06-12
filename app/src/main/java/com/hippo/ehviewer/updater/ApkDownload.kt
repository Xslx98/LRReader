package com.hippo.ehviewer.updater

import android.content.Context
import com.hippo.ehviewer.ServiceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Download from [url] to [dest]. Overwrites any existing file at [dest].
     *
     * Async via OkHttp [Call.enqueue] + [callbackFlow]:
     *   collect{} starts → enqueue posts the call to OkHttp's dispatcher → onResponse fires
     *   on OkHttp pool thread → stream-read body into dest, trySend InProgress @ 256 KB →
     *   trySend Success + close(). On any failure: trySend Failed + close(). On caller
     *   cancellation: awaitClose → call.cancel() + delete partial file.
     */
    fun download(url: String, dest: File): Flow<DownloadProgress> = callbackFlow {
        // The whole APK (15-25 MB) streams inside this single call; the shared client's
        // 30s callTimeout would abort it on slow links. Disable the call cap and rely on
        // read/connect timeouts to catch genuine stalls.
        val client = ServiceRegistry.networkModule.okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).build()
        val call = client.newCall(request)
        // Tracks whether we reached Success cleanly. Read from awaitClose to decide whether
        // to delete the dest file. AtomicBoolean because onResponse (OkHttp dispatcher thread)
        // and awaitClose (flow scope thread) may race.
        val success = AtomicBoolean(false)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    // Terminal event: must not be dropped if the 64-slot
                    // callbackFlow buffer filled up with InProgress ticks,
                    // or the consumer stalls forever at the last percent.
                    trySendBlocking(DownloadProgress.Failed(e))
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) {
                            trySendBlocking(DownloadProgress.Failed(IllegalStateException("HTTP ${response.code}")))
                            return
                        }
                        val body = response.body ?: run {
                            trySendBlocking(DownloadProgress.Failed(IllegalStateException("Empty response body")))
                            return
                        }
                        val totalBytes = body.contentLength()  // may be -1 for chunked
                        var downloaded = 0L
                        var lastEmit = 0L

                        body.byteStream().use { input ->
                            FileOutputStream(dest).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    if (call.isCanceled()) return
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    if (downloaded - lastEmit >= EMIT_INTERVAL_BYTES || lastEmit == 0L) {
                                        trySend(DownloadProgress.InProgress(downloaded, totalBytes))
                                        lastEmit = downloaded
                                    }
                                }
                            }
                        }
                        if (!call.isCanceled()) {
                            // Mark success BEFORE the final emissions so awaitClose, if it
                            // fires in the μs window between these statements, sees the file
                            // as complete and skips deletion. The trySend calls are racing
                            // with the consumer's potential cancel; success.set(true) wins.
                            success.set(true)
                            trySend(DownloadProgress.InProgress(downloaded, if (totalBytes > 0) totalBytes else downloaded))
                            // Terminal event: block until delivered so a full
                            // buffer can never strand the consumer at 100%.
                            trySendBlocking(DownloadProgress.Success)
                        }
                    } catch (t: Throwable) {
                        // Broad catch is intentional: onResponse runs on OkHttp's dispatcher
                        // pool (not a coroutine context), so CancellationException can never
                        // arrive here. Anything thrown is a genuine IO / parse / write error.
                        if (!call.isCanceled()) {
                            trySendBlocking(DownloadProgress.Failed(t))
                        }
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose {
            call.cancel()
            if (!success.get()) {
                runCatching { dest.delete() }
            }
        }
    }.flowOn(Dispatchers.IO)

    private const val BUFFER_SIZE = 8 * 1024              // 8 KB read buffer
    private const val EMIT_INTERVAL_BYTES = 256 * 1024L   // emit InProgress every 256 KB
}
