package com.lanraragi.reader.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of recent noteworthy events (audit 2026-10-04 C06,
 * ruling R3). Release builds strip `Log.v/d/i/w` with R8, so logcat holds
 * almost nothing between errors; this buffer is not `android.util.Log`, so it
 * survives minification and gives a crash report or a "Share diagnostics"
 * bundle the minutes before the failure.
 *
 * Every line is passed through [Redactor] on the way in. Nothing is written
 * to disk except as part of a crash report or a diagnostics bundle.
 */
class DiagRing(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lines = ArrayDeque<String>(capacity)
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun add(level: Char, tag: String, message: String, t: Throwable? = null) {
        val body = buildString {
            append(message)
            if (t != null) {
                append(" | ").append(t.javaClass.name)
                t.message?.let { append(": ").append(it) }
                var cause = t.cause
                var depth = 0
                while (cause != null && cause !== t && depth < MAX_CAUSES) {
                    append(" <- ").append(cause.javaClass.name)
                    cause.message?.let { append(": ").append(it) }
                    cause = cause.cause
                    depth++
                }
            }
        }
        val line = "${format(clock())} $level/$tag: ${Redactor.redact(body).take(MAX_LINE)}"
        synchronized(lines) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun tail(n: Int): List<String> = synchronized(lines) { lines.toList().takeLast(n) }

    fun clear() = synchronized(lines) { lines.clear() }

    private fun format(millis: Long): String = synchronized(format) { format.format(Date(millis)) }

    companion object {
        const val DEFAULT_CAPACITY = 500
        private const val MAX_LINE = 600
        private const val MAX_CAUSES = 4
    }
}

/** Process-wide [DiagRing]. */
object DiagLog {

    @JvmStatic
    val ring = DiagRing()

    @JvmStatic
    fun i(tag: String, message: String) = ring.add('I', tag, message)

    @JvmStatic
    @JvmOverloads
    fun w(tag: String, message: String, t: Throwable? = null) = ring.add('W', tag, message, t)

    @JvmStatic
    @JvmOverloads
    fun e(tag: String, message: String, t: Throwable? = null) = ring.add('E', tag, message, t)
}
