package com.lanraragi.reader.client.api

import android.content.Context
import android.util.Log
import androidx.collection.LruCache
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Shared utilities for all LRR API classes.
 * Shared across domain-specific API classes (LRRServerApi, LRRArchiveApi, etc.)
 */

private const val TAG = "LRRApi"

/**
 * Derive a stable 63-bit GID from an arcid using SHA-256.
 * Replaces the 32-bit hashCode() approach which has 50% collision probability at ~77K archives.
 * SHA-256 collision space is ~2^63, making real-world collisions astronomically unlikely.
 */
private val arcidGidCache = LruCache<String, Long>(1024)

internal fun arcidToGid(arcid: String): Long {
    if (arcid.isEmpty()) return 0L
    arcidGidCache.get(arcid)?.let { return it }
    val digest = MessageDigest.getInstance("SHA-256").digest(arcid.toByteArray(Charsets.UTF_8))
    val gid = ByteBuffer.wrap(digest, 0, 8).getLong() and Long.MAX_VALUE
    arcidGidCache.put(arcid, gid)
    return gid
}

/**
 * Parse [baseUrl] into an [okhttp3.HttpUrl], throwing a clear [IOException]
 * instead of crashing with NPE if the URL is malformed.
 */
internal fun parseBaseUrl(baseUrl: String): okhttp3.HttpUrl {
    return baseUrl.toHttpUrlOrNull()
        ?: throw IOException("Invalid server URL: $baseUrl")
}

/** Shared Json instance with lenient parsing. */
internal val lrrJson = Json { ignoreUnknownKeys = true }

/** Shared JSON media type constant. */
internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

/** Empty request body for POST/PUT calls that don't send data. */
internal val EMPTY_REQUEST_BODY: RequestBody = ByteArray(0).toRequestBody()

/** Thrown when the server returns a non-2xx HTTP status code. */
class LRRHttpException(val code: Int) : IOException("HTTP $code")

/** Thrown when the server returns a 2xx response but an empty body. */
class LRREmptyBodyException : IOException()

/** Thrown when a required field is missing from the server's JSON response. */
class LRRMissingFieldException(field: String) : IOException("Missing field: $field")

/**
 * Thrown by [LRRAuthInterceptor] when the configured server URL is malformed
 * (contains userInfo/fragment) or when a request would downgrade the scheme
 * (HTTP for an HTTPS-configured server or vice versa). In either case the
 * request is aborted before the API key leaves the device.
 */
class LRRPlaintextRefusedException(message: String) : IOException(message)

/**
 * Ensure the HTTP response is successful (2xx) and carries a JSON body.
 * Throws [LRRHttpException] on non-2xx status, or [IOException] if
 * the server returned a non-JSON content type (e.g., an HTML error page
 * from a reverse proxy).
 */
internal fun ensureSuccess(response: Response) {
    if (!response.isSuccessful) {
        throw LRRHttpException(response.code)
    }
    val contentType = response.body?.contentType()
    if (contentType != null && !contentType.subtype.contains("json")) {
        throw IOException("Expected JSON response but got $contentType")
    }
}

/**
 * Map common network exceptions to a localized user-friendly message.
 * Requires a [Context] to look up the appropriate string resource for the device locale.
 */
/** Thrown by [retryOnFailure] when the device has no network connection. */
class LRROfflineException : IOException("No network connection")

@JvmName("friendlyError")
fun friendlyError(context: Context, e: Exception): String {
    return when {
        e is LRROfflineException                 -> context.getString(R.string.lrr_offline_error)
        e is LRRCleartextRefusedException        -> context.getString(R.string.lrr_cleartext_refused_error)
        e is LRRHttpException -> when (e.code) {
            401, 403 -> context.getString(R.string.lrr_auth_failed_check_key)
            404      -> context.getString(R.string.lrr_not_found_404)
            in 500..503 -> context.getString(R.string.lrr_server_error_code, e.code)
            else     -> context.getString(R.string.lrr_request_failed_code, e.code)
        }
        e is LRREmptyBodyException           -> context.getString(R.string.lrr_empty_response)
        e is LRRMissingFieldException        -> context.getString(R.string.lrr_malformed_response)
        e is LRRPlaintextRefusedException    -> context.getString(R.string.lrr_plaintext_refused)
        e is java.net.SocketTimeoutException -> context.getString(R.string.lrr_timeout_error)
        e is java.net.ConnectException       -> context.getString(R.string.lrr_connect_error_check)
        e is java.net.UnknownHostException   -> context.getString(R.string.lrr_dns_error)
        e is javax.net.ssl.SSLException      -> context.getString(R.string.lrr_ssl_error)
        else -> e.message ?: e.javaClass.simpleName
    }
}

/**
 * Retry a suspending block on transient failures (IOException, 5xx).
 * Uses exponential backoff: 500ms -> 1000ms.
 *
 * @param maxRetries maximum number of retry attempts (default: 2)
 * @param block the suspend function to execute with retry
 */
internal suspend fun <T> retryOnFailure(
    maxRetries: Int = 2,
    block: suspend () -> T
): T {
    // Fast-fail when device is known to be offline — avoids waiting for connect timeout
    // runCatching guards against uninitialized ServiceRegistry in unit tests
    val isOffline = runCatching { !ServiceRegistry.networkModule.networkMonitor.isAvailable }.getOrDefault(false)
    if (isOffline) throw LRROfflineException()
    var lastException: Exception? = null
    repeat(maxRetries + 1) { attempt ->
        try {
            return block()
        } catch (e: IOException) {
            // Client errors (4xx) are permanent — retrying cannot fix them.
            if (e is LRRHttpException && e.code in 400..499) throw e
            lastException = e
            if (attempt < maxRetries) {
                val delayMs = 500L * (1 shl attempt) // 500, 1000
                Log.w(TAG, "Retry ${attempt + 1}/$maxRetries after ${delayMs}ms: ${e.message}")
                delay(delayMs)
            }
        }
    }
    throw lastException ?: IOException("Retry exhausted after ${maxRetries + 1} attempts")
}
