package com.lanraragi.reader.download

import com.lanraragi.framework.lib.yorozuya.FileUtils

/**
 * Naming rule for an archive's download directory: **the sanitised title,
 * nothing else**. Two archives that sanitise to the same title get
 * `Title (2)`, `Title (3)`, … — allocated by [uniqueName] against the
 * directory's siblings at creation time. The persisted `DOWNLOAD_DIRNAME`
 * row is the only link from an arcid to its directory; there is no
 * arcid prefix to scan for any more (pre-2026-09 directories were named
 * `<arcid>-<title>` and are renamed once at boot by `DownloadDirMigration`).
 */
object DownloadDirNaming {

    /** Filesystem limit the sanitiser enforces on a single path segment. */
    private const val MAX_NAME_BYTES = 255

    /**
     * The name a fresh directory for [arcid] / [title] starts from. An
     * empty or fully-stripped title falls back to the arcid so the
     * directory is still addressable and unique.
     */
    fun baseName(arcid: String, title: String?): String {
        val sanitised = title?.let { FileUtils.sanitizeFilename(it) }.orEmpty().trim()
        return sanitised.ifEmpty { arcid }
    }

    /** True for a directory name produced by the old `<arcid>-<title>` rule. */
    fun isLegacyName(arcid: String, dirname: String): Boolean = dirname.startsWith("$arcid-")

    /**
     * First of `base`, `base (2)`, `base (3)`, … for which [exists] is
     * false. The suffix always fits within [MAX_NAME_BYTES]; a base that
     * is already at the limit is trimmed to make room.
     */
    fun uniqueName(base: String, exists: (String) -> Boolean): String {
        if (!exists(base)) return base
        var n = 2
        while (true) {
            val candidate = withSuffix(base, " ($n)")
            if (!exists(candidate)) return candidate
            n++
        }
    }

    internal fun withSuffix(base: String, suffix: String): String {
        val room = MAX_NAME_BYTES - utf8Length(suffix)
        var trimmed = base
        while (utf8Length(trimmed) > room && trimmed.isNotEmpty()) {
            trimmed = trimmed.dropLast(1)
        }
        return trimmed.trimEnd() + suffix
    }

    private fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
