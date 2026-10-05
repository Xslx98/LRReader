package com.lanraragi.reader.diagnostics

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local crash/non-fatal/exit-reason reports in an app-private directory
 * (audit 2026-10-04 C06, ruling R1): never uploaded, the newest [keep] of
 * each [Kind] kept, the rest pruned on every write. The user ships them
 * only by sharing a diagnostics bundle.
 */
class CrashLogStore(
    private val dir: File,
    private val keep: Int = DEFAULT_KEEP,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Kind(val prefix: String) {
        /** The process died on an uncaught exception. */
        CRASH("crash-"),

        /** An exception reached a coroutine exception handler; the app lived on. */
        NON_FATAL("nonfatal-"),

        /** A previous process ended in an ANR or native crash (`ApplicationExitInfo`). */
        EXIT("exit-"),
    }

    /** Writes [body] as a new report and prunes older ones of the same kind. */
    @Throws(IOException::class)
    fun write(kind: Kind, body: String): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val stamp = synchronized(FORMAT) { FORMAT.format(Date(clock())) }
        var file = File(dir, "${kind.prefix}$stamp.log")
        var n = 1
        while (file.exists()) file = File(dir, "${kind.prefix}$stamp-${n++}.log")
        file.writeText(body, Charsets.UTF_8)
        prune(kind)
        return file
    }

    /** All reports, newest first. */
    fun list(): List<File> {
        val files = dir.listFiles { f -> f.isFile && Kind.entries.any { f.name.startsWith(it.prefix) } }
        if (files == null) return emptyList()
        // The stamp in the name, not lastModified: some filesystems keep whole seconds.
        return files.sortedByDescending { stampOf(it.name) }
    }

    private fun stampOf(name: String): String {
        val kind = Kind.entries.first { name.startsWith(it.prefix) }
        return name.removePrefix(kind.prefix).removeSuffix(".log").padEnd(STAMP_SORT_WIDTH, ' ')
    }

    private fun prune(kind: Kind) {
        list().filter { it.name.startsWith(kind.prefix) }.drop(keep).forEach { it.delete() }
    }

    companion object {
        const val DEFAULT_KEEP = 5

        // "yyyyMMdd-HHmmss-SSS" plus room for a "-n" collision suffix.
        private const val STAMP_SORT_WIDTH = 24
        private val FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }
}
