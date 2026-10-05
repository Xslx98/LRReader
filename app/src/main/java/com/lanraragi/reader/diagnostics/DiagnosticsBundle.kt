package com.lanraragi.reader.diagnostics

import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The "Share diagnostics" zip (audit 2026-10-04 C06, ruling R2). Built only
 * when the user taps the button and handed to the share sheet; never
 * uploaded by the app. Contents:
 *
 * - `info.txt` — app, database, device, server (scheme + LAN only) and
 *   download-queue summary (counts and failure reasons, no titles);
 * - `settings.txt` — preferences after [renderSettings] (no keys, hosts,
 *   paths, labels or free text);
 * - `events.txt` — the redacted [DiagLog] ring;
 * - `logcat.txt` — this process's redacted logcat;
 * - `reports/` — the local crash, non-fatal and exit reports.
 */
class DiagnosticsBundle(private val sources: Sources) {

    interface Sources {
        fun info(): String
        fun settings(): Map<String, *>
        fun events(): List<String>
        fun reports(): List<File>
        fun writeLogcat(out: java.io.Writer): Boolean
    }

    @Throws(IOException::class)
    fun writeTo(target: File) {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            val writer = OutputStreamWriter(zip, Charsets.UTF_8)
            fun text(name: String, body: () -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                body()
                writer.flush()
                zip.closeEntry()
            }
            text("info.txt") { writer.write(sources.info()) }
            text("settings.txt") { writer.write(renderSettings(sources.settings())) }
            text("events.txt") { sources.events().forEach { writer.write(it); writer.write("\n") } }
            text("logcat.txt") {
                if (!sources.writeLogcat(writer)) writer.write("(logcat unavailable)\n")
            }
            for (report in sources.reports()) {
                zip.putNextEntry(ZipEntry("reports/" + report.name))
                report.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    companion object {
        /**
         * Key fragments whose values never leave the device: credentials,
         * server addresses, storage locations and anything user-named.
         */
        private val SENSITIVE_KEY = Regex(
            "(?i)(key|token|pass|secret|auth|cookie|url|host|server|address|proxy|path|uri|scheme|" +
                "query|fragment|label|search|history|profile|arcid|title|categor|tag|name|pin|lock|" +
                "outbox|interrupted|last_)"
        )

        /** Enum-like values: list-preference ids, numbers, theme names. */
        private val PLAIN_VALUE = Regex("^[A-Za-z0-9_.-]{0,32}$")

        /**
         * Renders preferences for the bundle. Booleans and numbers are kept;
         * strings only when they look like a list-preference id; string sets
         * as a count; every sensitive or URL-shaped key is dropped entirely.
         */
        fun renderSettings(prefs: Map<String, *>): String = buildString {
            for ((key, value) in prefs.toSortedMap()) {
                if (SENSITIVE_KEY.containsMatchIn(key) || key.contains('/') || key.contains(':')) continue
                val shown = when (value) {
                    is Boolean, is Int, is Long, is Float -> value.toString()
                    is String -> if (PLAIN_VALUE.matches(value)) value else "<text>"
                    is Set<*> -> "<set of ${value.size}>"
                    null -> "null"
                    else -> "<${value.javaClass.simpleName}>"
                }
                append(key).append('=').append(shown).append('\n')
            }
        }
    }
}
