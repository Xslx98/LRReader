package com.hippo.ehviewer.client.lrr

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.R
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Shared utilities for all LRR API classes.
 * Shared across domain-specific API classes (LRRServerApi, LRRArchiveApi, etc.)
 */

private const val TAG = "LRRApi"

/** Shared Json instance with lenient parsing. */
internal val lrrJson = Json { ignoreUnknownKeys = true }

/** Shared JSON media type constant. */
internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

/** Empty request body for POST/PUT calls that don't send data. */
internal val EMPTY_REQUEST_BODY: RequestBody = ByteArray(0).toRequestBody()

/** Thrown when the server returns a non-2xx HTTP status code. */
class LRRHttpException(val code: Int) : IOException("HTTP $code")

/**
 * Ensure the HTTP response is successful (2xx).
 * Throws [LRRHttpException] carrying the status code if not.
 */
internal fun ensureSuccess(response: Response) {
    if (!response.isSuccessful) {
        // Body is not consumed here; the caller's use{} block will close() it.
        // On error responses this discards the TCP connection rather than returning
        // it to the pool — acceptable since error rates should be low.
        throw LRRHttpException(response.code)
    }
}

/**
 * Map common network exceptions to a localized user-friendly message.
 * Requires a [Context] to look up the appropriate string resource for the device locale.
 */
@JvmName("friendlyError")
fun friendlyError(context: Context, e: Exception): String {
    return when {
        e is LRRHttpException -> when (e.code) {
            401, 403 -> context.getString(R.string.lrr_auth_failed_check_key)
            404      -> context.getString(R.string.lrr_not_found_404)
            in 500..503 -> context.getString(R.string.lrr_server_error_code, e.code)
            else     -> context.getString(R.string.lrr_request_failed_code, e.code)
        }
        e is java.net.SocketTimeoutException -> context.getString(R.string.lrr_timeout_error)
        e is java.net.ConnectException       -> context.getString(R.string.lrr_connect_error_check)
        e is java.net.UnknownHostException   -> context.getString(R.string.lrr_dns_error)
        e is javax.net.ssl.SSLException      -> context.getString(R.string.lrr_ssl_error)
        else -> e.message ?: e.javaClass.simpleName
    }
}

/**
 * Retry a suspending block on transient failures (IOException, 5xx).
 * Uses exponential backoff: 500ms → 1000ms.
 *
 * @param maxRetries maximum number of retry attempts (default: 2)
 * @param block the suspend function to execute with retry
 */
internal suspend fun <T> retryOnFailure(
    maxRetries: Int = 2,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    repeat(maxRetries + 1) { attempt ->
        try {
            return block()
        } catch (e: IOException) {
            lastException = e
            if (attempt < maxRetries) {
                val delayMs = 500L * (1 shl attempt) // 500, 1000
                Log.w(TAG, "Retry ${attempt + 1}/$maxRetries after ${delayMs}ms: ${e.message}")
                delay(delayMs)
            }
        }
    }
    throw lastException!!
}
