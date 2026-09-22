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

    /** File extension (with the dot) of a server page path, `.jpg` when absent. */
    fun extensionOf(pagePath: String): String {
        val dot = pagePath.lastIndexOf('.')
        return if (dot >= 0) pagePath.substring(dot) else DEFAULT_EXTENSION
    }

    /**
     * The page file for 0-based [index] whose server path is [pagePath].
     * Formatted with [Locale.US]: the default locale may use non-ASCII
     * digits (Arabic, Persian), and then the worker and the reader would
     * name the same page differently.
     */
    fun pageFile(downloadDir: File, index: Int, pagePath: String): File =
        File(downloadDir, String.format(Locale.US, "%04d%s", index + 1, extensionOf(pagePath)))
}
