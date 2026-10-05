package com.lanraragi.reader.download

import java.io.File
import java.util.Locale

/**
 * The one definition of how a page is named inside an archive's download
 * directory: `0001.jpg`, `0002.png`, … (1-based, zero-padded to 4 digits,
 * extension taken from the server page path, `.jpg` when it has none).
 *
 * Shared by [LRRDownloadWorker] (the writer) and the reader's hybrid mode
 * (`LRRGalleryProvider` with a download directory), which reads pages the
 * worker has already landed and writes the ones it fetches itself under the
 * same names so the worker later skips them. Any change here must keep the
 * two sides in lockstep — and keep existing downloads on disk resolvable.
 */
object DownloadPageNaming {

    private const val DEFAULT_EXTENSION = ".jpg"

    /**
     * File extension (with the dot) of a server page path, `.jpg` when absent
     * or not 1-5 ASCII letters/digits — the path is server data and the old
     * rule kept everything after the last dot, separators included (audit
     * C48 / SEC-09). Valid extensions keep their case so existing downloads
     * still resolve.
     */
    fun extensionOf(pagePath: String): String {
        val dot = pagePath.lastIndexOf('.')
        if (dot < 0) return DEFAULT_EXTENSION
        val ext = pagePath.substring(dot + 1)
        val valid = ext.length in 1..MAX_EXTENSION_LENGTH && ext.all { it.code < 128 && it.isLetterOrDigit() }
        return if (valid) ".$ext" else DEFAULT_EXTENSION
    }

    private const val MAX_EXTENSION_LENGTH = 5

    /**
     * The page file for 0-based [index] whose server path is [pagePath].
     * Formatted with [Locale.US]: the default locale may use non-ASCII
     * digits (Arabic, Persian), and then the worker and the reader would
     * name the same page differently.
     */
    fun pageFile(downloadDir: File, index: Int, pagePath: String): File =
        File(downloadDir, String.format(Locale.US, "%04d%s", index + 1, extensionOf(pagePath)))
}
