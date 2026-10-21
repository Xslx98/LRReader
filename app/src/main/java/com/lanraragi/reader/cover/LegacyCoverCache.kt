package com.lanraragi.reader.cover

import java.io.File

/**
 * Cache directories left behind by the removed Conaco/BeerBelly loader:
 * `thumb` (the cover disk cache, 80-320 MB) and `spider_info` (an unused
 * 5 MB cache). Nothing reads or trims them any more, so upgraded installs
 * would carry them forever; [purge] deletes them once.
 */
object LegacyCoverCache {

    internal val DIRS = listOf("thumb", "spider_info")

    /** Deletes the legacy directories under [cacheDir]; true when none is left. */
    fun purge(cacheDir: File): Boolean =
        DIRS.map { File(cacheDir, it) }.all { !it.exists() || it.deleteRecursively() }
}
