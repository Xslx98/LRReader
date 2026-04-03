package com.hippo.ehviewer.client.lrr

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Shared utilities for all LRR API classes.
 *
 * Shared utilities for all LRR API classes.
 * across domain-specific API classes (LRRServerApi, LRRArchiveApi, etc.)
 */

private const val TAG = "LRRApi"

/** Shared Json instance with lenient parsing. */
internal val lrrJson = Json { ignoreUnknownKeys = true }

/** Shared JSON media type constant. */
internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

/** Empty request body for POST/PUT calls that don't send data. */
internal val EMPTY_REQUEST_BODY: RequestBody = ByteArray(0).toRequestBody()

/**
 * Ensure the HTTP response is successful (2xx).
 * Throws [IOException] with a user-friendly message if not.
 */
internal fun ensureSuccess(response: Response) {
    if (!response.isSuccessful) {
        val friendlyMsg = when (response.code) {
            401, 403 -> "认证失败，请检查 API Key 是否正确"
            404 -> "资源未找到 (404)"
            in 500..503 -> "服务器错误 (${response.code})，请稍后重试"
            else -> "请求失败 (HTTP ${response.code})"
        }
        throw IOException(friendlyMsg)
    }
}

/**
 * Map common network exceptions to user-friendly Chinese messages.
 */
@JvmName("friendlyError")
fun friendlyError(e: Exception): String {
    val msg = e.message ?: e.javaClass.simpleName

    return when {
        e is java.net.SocketTimeoutException -> "连接超时，请检查网络或服务器状态"
        e is java.net.ConnectException -> "无法连接到服务器，请检查地址和网络"
        e is java.net.UnknownHostException -> "无法解析服务器地址，请检查 URL"
        e is javax.net.ssl.SSLException -> "安全连接失败，请检查服务器证书"
        msg.startsWith("认证失败") || msg.startsWith("服务器错误")
            || msg.startsWith("请求失败") || msg.startsWith("资源未找到") -> msg
        else -> msg
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
