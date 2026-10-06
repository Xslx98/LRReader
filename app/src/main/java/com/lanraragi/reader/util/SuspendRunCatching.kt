package com.lanraragi.reader.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for code that calls suspend functions (audit 2026-10-04 C33).
 * Plain `runCatching` turns a [CancellationException] into an ordinary failure,
 * so a cancelled coroutine carries on with its fallback value instead of
 * stopping; this rethrows it. Use it whenever the block can suspend.
 *
 * (detekt's SuspendFunSwallowedCancellation cannot see suspend calls here:
 * its type resolution does not read this Kotlin version's coroutine metadata.)
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
