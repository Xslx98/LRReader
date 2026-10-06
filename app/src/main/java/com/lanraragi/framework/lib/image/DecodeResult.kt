package com.lanraragi.framework.lib.image

import android.util.Log
import com.lanraragi.reader.Analytics
import com.lanraragi.reader.diagnostics.DiagLog

/**
 * Outcome of a decode that keeps the reason for a failure (audit 2026-10-06d
 * PERF-01): the reader must tell "the decoder ran out of memory" apart from
 * "the decoder rejected the bytes", because only the second can mean a
 * damaged file worth deleting and fetching again.
 */
sealed interface DecodeResult<out T> {
    data class Ok<T>(val value: T) : DecodeResult<T>

    /** OutOfMemoryError at sample x1 and again at [Image.OOM_RETRY_MULTIPLIER]. */
    data object OutOfMemory : DecodeResult<Nothing>

    /** The decoder threw: unsupported format or damaged bytes (the caller decides which). */
    data class Failed(val cause: Exception) : DecodeResult<Nothing>
}

private const val TAG = "Image"

/**
 * Runs [attempt] at sample multiplier 1; on OutOfMemoryError rewinds and
 * retries once at [Image.OOM_RETRY_MULTIPLIER] (audit 2026-10-04 C11, user
 * ruling: no general pixel cap). A second OOM yields [DecodeResult.OutOfMemory],
 * any exception [DecodeResult.Failed].
 */
internal fun <T> decodeWithOomRetry(rewind: () -> Unit, attempt: (Int) -> T): DecodeResult<T> {
    for (multiplier in intArrayOf(1, Image.OOM_RETRY_MULTIPLIER)) {
        try {
            if (multiplier > 1) rewind()
            return DecodeResult.Ok(attempt(multiplier))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Decode ran out of memory at sample x$multiplier", e)
            DiagLog.e(TAG, "Decode OOM at sample x$multiplier", e)
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed", e)
            Analytics.recordException(e)
            return DecodeResult.Failed(e)
        }
    }
    return DecodeResult.OutOfMemory
}
