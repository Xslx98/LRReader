/*
 * Analytics stub for LRReader.
 * Firebase has been removed. All analytics methods are no-ops.
 */
package com.lanraragi.reader

import android.util.Log

/**
 * Stub analytics — all methods are no-ops since Firebase was removed.
 */
object Analytics {
    private const val LOG_TAG = "Analytics"

    @JvmStatic
    fun recordException(e: Throwable) {
        Log.e(LOG_TAG, "Unexpected error raised", e)
        com.lanraragi.reader.diagnostics.DiagLog.e(LOG_TAG, "Unexpected error raised", e)
    }
}
