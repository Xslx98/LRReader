package com.lanraragi.reader.client.api

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.IOException

/**
 * Shared utilities for all LRR API classes.
 * Shared across domain-specific API classes (LRRServerApi, LRRArchiveApi, etc.)
 */

private const val TAG = "LRRApi"

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

/**
 * Thrown when the server returns a non-2xx HTTP status code. [serverError]
 * carries the `error` message from the response body when LANraragi sent a JSON
 * `{success:0, error:"…"}` envelope (e.g. 409 duplicate, 417 checksum, 423
 * locked, 400 "Server-side Progress Tracking is disabled"), so callers can
 * surface the server's reason instead of a bare status code. Null when the body
 * was absent or not a JSON error envelope (e.g. an HTML reverse-proxy page).
 */
class LRRHttpException(val code: Int, val serverError: String? = null) :
    IOException(serverError ?: "HTTP $code")

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
 * Thrown by client-side validators when an API method is called with an
 * argument that the OpenAPI spec would reject (wrong length, wrong format).
 * Catching this client-side prevents the malformed request from leaving the
 * device — strictly cheaper and more debuggable than waiting for the server
 * to 422 it.
 */
class LRRClientValidationException(message: String) : IllegalArgumentException(message)

/**
 * Validate that [arcid] is a 40-character lowercase SHA-1 hex string,
 * matching the `id` path parameter constraint on every `archives/{id}/...`
 * endpoint in the OpenAPI spec (`minLength: 40, maxLength: 40`).
 *
 * Returns the input unchanged so call sites can chain inline:
 * `addPathSegment(requireValidArcid(arcid))`.
 */
internal fun requireValidArcid(arcid: String): String {
    if (arcid.length != 40 || !arcid.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
        throw LRRClientValidationException(
            "Invalid archive id (expected 40-char SHA-1 hex): '$arcid' (len=${arcid.length})"
        )
    }
    return arcid
}

/** Prefix of LANraragi Tankoubon IDs (e.g. "TANK_1688616437"). */
internal const val TANKOUBON_ID_PREFIX = "TANK_"

/**
 * True if [id] is a Tankoubon ID (15-char `TANK_##########`) rather than a
 * 40-char archive SHA-1. Search with `groupby_tanks` enabled mixes these into
 * the results; the archive pipeline (toArchive → getThumbnailUrl →
 * requireValidArcid) can't render them, so browse/search paths filter them out
 * defensively (the request also sends `groupby_tanks=false`).
 */
internal fun isTankoubonId(id: String): Boolean = id.startsWith(TANKOUBON_ID_PREFIX)

/**
 * Validate that [categoryId] is exactly 14 characters long, matching the
 * spec's `id` constraint on category endpoints (`minLength: 14, maxLength: 14`).
 * LANraragi category IDs are formatted `SET_xxxxxxxxxx`.
 *
 * Returns the input unchanged so call sites can chain inline.
 */
internal fun requireValidCategoryId(categoryId: String): String {
    if (categoryId.length != 14) {
        throw LRRClientValidationException(
            "Invalid category id (expected 14 chars): '$categoryId' (len=${categoryId.length})"
        )
    }
    return categoryId
}

/**
 * Ensure the HTTP response is successful (2xx) and carries a JSON body.
 * Throws [LRRHttpException] on non-2xx status, or [IOException] if
 * the server returned a non-JSON content type (e.g., an HTML error page
 * from a reverse proxy).
 */
internal fun ensureSuccess(response: Response) {
    if (!response.isSuccessful) {
        // Surface the server's JSON error message when present. LANraragi sends a
        // `{success:0, error:"…"}` body on documented non-2xx responses (409
        // duplicate, 417 checksum, 423 locked, 400 "tracking disabled", …) that a
        // bare status code throws away. Reading the body here is safe: the caller
        // never reads it on the error path because this throws.
        val serverError = runCatching { response.body?.string()?.let(::parseLrrError) }.getOrNull()
        throw LRRHttpException(response.code, serverError)
    }
    val contentType = response.body?.contentType()
    if (contentType != null && !contentType.subtype.contains("json")) {
        throw IOException("Expected JSON response but got $contentType")
    }
}

/**
 * Extract the `error` message from a LANraragi JSON error envelope, or null if
 * [body] isn't JSON or has no non-blank `error` field (e.g. a plain-text/HTML
 * error page from a reverse proxy).
 */
private fun parseLrrError(body: String): String? = runCatching {
    lrrJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}.getOrNull()

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
        // Prefer the server's own error message when LANraragi provided one
        // (e.g. duplicate archive, locked resource, progress tracking disabled).
        e is LRRHttpException && !e.serverError.isNullOrBlank() -> e.serverError
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
