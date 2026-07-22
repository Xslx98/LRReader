package com.hippo.ehviewer.download

import java.io.File

/**
 * Offline completeness check for an already-downloaded archive directory.
 *
 * Used by [LRRDownloadWorker] when the page-list fetch fails: if every page
 * the last-known metadata promises is present and valid on disk, the run is
 * reported as finished instead of failed, so an unreachable source profile
 * (the WAN endpoint of a server also reachable via LAN, or a server that is
 * simply offline) cannot flip a completed download to FAILED.
 */
object LocalArchiveVerifier {

    /**
     * True when [dir] holds a valid image for every page `1..pagecount`.
     *
     * The worker stores pages as `"%04d<ext>"` ([LRRDownloadWorker.getExtension]
     * keeps the dot), but the extension is unknown offline — any non-`.tmp`
     * file whose leading digits parse to the page number counts as that
     * page's candidate. A candidate is valid when it meets [minSizeBytes]
     * (mirrors the worker's resume skip) and passes [validate].
     */
    fun isComplete(
        dir: File?,
        pagecount: Int,
        minSizeBytes: Long = LRRDownloadWorker.MIN_IMAGE_SIZE,
        validate: (File) -> Boolean = { LRRDownloadWorker.validateImageFile(it) },
    ): Boolean {
        if (dir == null || pagecount <= 0 || !dir.isDirectory) return false
        val files = dir.listFiles() ?: return false
        val candidates = HashMap<Int, MutableList<File>>()
        for (f in files) {
            if (!f.isFile || f.name.endsWith(".tmp")) continue
            val dot = f.name.indexOf('.')
            if (dot <= 0) continue
            val page = f.name.substring(0, dot).toIntOrNull() ?: continue
            candidates.getOrPut(page) { mutableListOf() }.add(f)
        }
        return (1..pagecount).all { page ->
            candidates[page].orEmpty().any { it.length() >= minSizeBytes && validate(it) }
        }
    }
}
