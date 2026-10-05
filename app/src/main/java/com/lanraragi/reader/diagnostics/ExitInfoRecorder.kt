package com.lanraragi.reader.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.InputStream

/**
 * Records how earlier processes died when the JVM handler never ran (audit
 * 2026-10-04 C06, ruling R5): ANRs, native crashes in `liblrreader.so` and
 * start-up failures, read at boot from `ApplicationExitInfo` (API 30+) and
 * written as [CrashLogStore.Kind.EXIT] reports. A timestamp watermark makes
 * each exit reported once.
 */
class ExitInfoRecorder(
    private val store: CrashLogStore,
    private val prefs: SharedPreferences,
) {

    /** The fields we report; a seam so tests need no `ApplicationExitInfo`. */
    class ExitRecord(
        val timestamp: Long,
        val reason: Int,
        val description: String?,
        val importance: Int,
        val status: Int,
        val pssKb: Long,
        val rssKb: Long,
        val trace: () -> String?,
    )

    /** @return the number of reports written. */
    fun record(records: List<ExitRecord>): Int {
        val watermark = prefs.getLong(KEY_WATERMARK, 0L)
        val fresh = records
            .filter { it.timestamp > watermark && it.reason in REPORTED_REASONS }
            .sortedBy { it.timestamp }
        var written = 0
        for (r in fresh) {
            try {
                store.write(CrashLogStore.Kind.EXIT, format(r))
                written++
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Write exit report", e)
            }
        }
        val newest = records.maxOfOrNull { it.timestamp } ?: 0L
        if (newest > watermark) prefs.edit().putLong(KEY_WATERMARK, newest).apply()
        return written
    }

    internal fun format(r: ExitRecord): String = buildString {
        append("======== ExitInfo ========\n")
        append("Reason=").append(reasonName(r.reason)).append('\n')
        append("Timestamp=").append(r.timestamp).append('\n')
        append("Description=").append(r.description?.let(Redactor::redact) ?: "null").append('\n')
        append("Importance=").append(r.importance).append('\n')
        append("Status=").append(r.status).append('\n')
        append("PssKb=").append(r.pssKb).append('\n')
        append("RssKb=").append(r.rssKb).append('\n')
        val trace = try {
            r.trace()
        } catch (e: Exception) {
            Log.w(TAG, "Read exit trace", e)
            null
        }
        if (trace != null) {
            append("\n======== Trace ========\n")
            append(Redactor.redact(trace))
            append('\n')
        }
    }

    companion object {
        private const val TAG = "ExitInfoRecorder"
        private const val KEY_WATERMARK = "diag_exit_info_watermark"
        private const val MAX_RECORDS = 16
        internal const val MAX_TRACE_BYTES = 256 * 1024

        // ApplicationExitInfo.REASON_* values (stable API constants).
        internal const val REASON_CRASH_NATIVE = 5
        internal const val REASON_ANR = 6
        internal const val REASON_INITIALIZATION_FAILURE = 7

        /** A plain Java crash is already written by the uncaught handler. */
        private val REPORTED_REASONS = setOf(REASON_CRASH_NATIVE, REASON_ANR, REASON_INITIALIZATION_FAILURE)

        internal fun reasonName(reason: Int): String = when (reason) {
            REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            REASON_ANR -> "ANR"
            REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            else -> "REASON_$reason"
        }

        internal fun readCapped(input: InputStream, max: Int = MAX_TRACE_BYTES): String {
            val bytes = input.use { s ->
                val buf = ByteArray(max)
                var n = 0
                while (n < max) {
                    val read = s.read(buf, n, max - n)
                    if (read < 0) break
                    n += read
                }
                buf.copyOf(n)
            }
            val text = String(bytes, Charsets.UTF_8)
            return if (bytes.size == max) "$text\n…(truncated at $max bytes)" else text
        }

        /** Reads this package's exit history and records what is new. API 30+ only. */
        @JvmStatic
        fun recordAtBoot(context: Context, store: CrashLogStore, prefs: SharedPreferences): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
            val am = context.getSystemService(ActivityManager::class.java)
            if (am == null) return 0
            val infos = am.getHistoricalProcessExitReasons(null, 0, MAX_RECORDS)
            return ExitInfoRecorder(store, prefs).record(infos.map(::toRecord))
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun toRecord(info: ApplicationExitInfo) = ExitRecord(
            timestamp = info.timestamp,
            reason = info.reason,
            description = info.description,
            importance = info.importance,
            status = info.status,
            pssKb = info.pss,
            rssKb = info.rss,
            // Only an ANR trace is text; a native tombstone (API 31+) is a protobuf.
            trace = {
                if (info.reason == ApplicationExitInfo.REASON_ANR) {
                    info.traceInputStream?.let { readCapped(it) }
                } else {
                    null
                }
            },
        )
    }
}
