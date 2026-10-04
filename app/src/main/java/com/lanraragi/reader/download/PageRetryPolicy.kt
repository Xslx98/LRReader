package com.lanraragi.reader.download

import android.system.ErrnoException
import android.system.OsConstants
import com.lanraragi.reader.client.api.LRRHttpException
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.min
import kotlin.random.Random

/** Why a download failed, as far as the app can tell (audit 2026-10-04 C21). */
enum class DownloadFailureReason {
    /** The device ran out of storage. */
    NO_SPACE,

    /** The server refused the request (401/403): API key missing or rotated. */
    AUTH,

    /** The archive or page no longer exists on the server (404/410). */
    NOT_FOUND,

    /** The server answered 5xx or 429. */
    SERVER,

    /** The connection failed or timed out while the network was up. */
    NETWORK,

    /** The server answered, but not with a usable image. */
    CORRUPT,

    UNKNOWN,
}

/** A non-2xx answer; [retryAfterMillis] is the parsed `Retry-After`, if any. */
class HttpStatusException(val code: Int, val retryAfterMillis: Long? = null) : IOException("HTTP $code")

/** A response that is not a usable image (bad magic bytes, too small). */
class CorruptPageException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Page-level retry policy of [LRRDownloadWorker] (audit 2026-10-04 C21).
 *
 * Before, every failure got one immediate retry: a server busy extracting a
 * large archive failed all eight in-flight pages within a second, and a
 * rotated API key cost two doomed requests per remaining page. Now:
 *  - permanent failures (401/403/404/410, storage full) abort the whole
 *    archive at once;
 *  - transient ones (5xx, 429, 408, connection errors) back off
 *    exponentially with jitter, honouring `Retry-After`;
 *  - anything else keeps the old two attempts, one second apart.
 */
object PageRetryPolicy {
    const val TRANSIENT_ATTEMPTS = 4
    const val OTHER_ATTEMPTS = 2
    const val BASE_DELAY_MS = 1_000L
    const val MAX_BACKOFF_MS = 30_000L
    const val MAX_RETRY_AFTER_MS = 60_000L
    private const val JITTER = 0.2

    sealed interface Decision {
        /** Try the same page again after [delayMillis]. */
        data class Retry(val delayMillis: Long) : Decision

        /** Give up on this page; other pages go on. */
        data class Fail(val reason: DownloadFailureReason) : Decision

        /** Give up on the whole archive: no other page can succeed either. */
        data class Abort(val reason: DownloadFailureReason) : Decision
    }

    fun classify(e: Throwable): DownloadFailureReason = when {
        isNoSpace(e) -> DownloadFailureReason.NO_SPACE
        e is HttpStatusException -> forStatus(e.code)
        // The page-list call goes through the API layer's ensureSuccess.
        e is LRRHttpException -> forStatus(e.code)
        e is CorruptPageException -> DownloadFailureReason.CORRUPT
        e is IOException -> DownloadFailureReason.NETWORK
        else -> DownloadFailureReason.UNKNOWN
    }

    private fun forStatus(code: Int): DownloadFailureReason = when (code) {
        401, 403 -> DownloadFailureReason.AUTH
        404, 410 -> DownloadFailureReason.NOT_FOUND
        408, 429 -> DownloadFailureReason.SERVER
        in 500..599 -> DownloadFailureReason.SERVER
        else -> DownloadFailureReason.UNKNOWN
    }

    /**
     * What to do after [attempt] (1-based) failed with [e] while the network
     * was up. Network-down failures never reach this: the worker waits for
     * the network without spending an attempt.
     */
    fun decide(e: Throwable, attempt: Int, random: Random = Random.Default): Decision {
        val reason = classify(e)
        return when (reason) {
            DownloadFailureReason.NO_SPACE,
            DownloadFailureReason.AUTH,
            DownloadFailureReason.NOT_FOUND -> Decision.Abort(reason)

            DownloadFailureReason.SERVER,
            DownloadFailureReason.NETWORK ->
                if (attempt >= TRANSIENT_ATTEMPTS) {
                    Decision.Fail(reason)
                } else {
                    val retryAfter = (e as? HttpStatusException)?.retryAfterMillis
                    Decision.Retry(retryAfter?.coerceIn(0, MAX_RETRY_AFTER_MS) ?: backoff(attempt, random))
                }

            DownloadFailureReason.CORRUPT,
            DownloadFailureReason.UNKNOWN ->
                if (attempt >= OTHER_ATTEMPTS || (e is HttpStatusException)) {
                    // An unexpected 4xx will not change on a retry.
                    Decision.Fail(reason)
                } else {
                    Decision.Retry(BASE_DELAY_MS)
                }
        }
    }

    /** 1 s, 2 s, 4 s … capped at [MAX_BACKOFF_MS], ±20 % jitter. */
    internal fun backoff(attempt: Int, random: Random): Long {
        val exp = BASE_DELAY_MS shl min(attempt - 1, 15)
        val base = min(exp, MAX_BACKOFF_MS)
        val jitter = (base * JITTER * (random.nextDouble() * 2 - 1)).toLong()
        return base + jitter
    }

    /** `Retry-After` in delta-seconds; the HTTP-date form is ignored. */
    fun parseRetryAfter(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }

    private fun isNoSpace(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { t ->
            (t is ErrnoException && t.errno == OsConstants.ENOSPC) ||
                (t is IOException && t !is InterruptedIOException && t.message?.contains("ENOSPC") == true)
        }

    private const val MAX_CAUSE_DEPTH = 8
}
