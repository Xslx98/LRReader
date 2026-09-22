package com.lanraragi.reader.download

import java.util.concurrent.ConcurrentHashMap

/**
 * On-disk size of each download's directory, for "sort by file size".
 *
 * The size used to live on the DownloadInfo instance, which the Room-driven
 * list replaces on every structural change, so each sort walked every
 * download directory again; a missing directory stored -1, meaning
 * "recompute". Sizes now live here by arcid; a missing directory counts
 * as 0, and an entry is dropped whenever that download's files can
 * change (download finished, deleted).
 */
object DownloadSizeCache {

    private val sizes = ConcurrentHashMap<String, Long>()

    fun get(arcid: String): Long? = sizes[arcid]

    fun put(arcid: String, bytes: Long) {
        sizes[arcid] = bytes.coerceAtLeast(0L)
    }

    fun invalidate(arcid: String) {
        sizes.remove(arcid)
    }
}
