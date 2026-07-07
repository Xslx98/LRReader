/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.gallery

import android.content.Context
import com.hippo.lib.image.Image
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.StringUtils
import com.hippo.unifile.UniFile
import com.hippo.util.NaturalComparator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Shared helpers for directory-backed gallery sources
 * ([DirGalleryProvider] and [ReaderPageCache]'s `warmDir`).
 *
 * Pulled out so the file-listing / sort / decode logic for a folder
 * of pages lives in exactly one place — the previous version had
 * private duplicates in DirGalleryProvider that the warmup path
 * couldn't reuse.
 */
internal object DirImageFiles {

    /** Filter for files whose extensions match any supported image format. */
    val imageFilter = com.hippo.unifile.FilenameFilter { _, name ->
        StringUtils.endsWith(name.lowercase(), GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS)
    }

    /** Natural-order comparator (1.png < 2.png < 10.png) by filename. */
    val naturalComparator = Comparator<UniFile> { o1, o2 ->
        NaturalComparator().compare(o1.name, o2.name)
    }

    /**
     * List image files in [dir] sorted naturally by name. Returns null
     * if the directory cannot be enumerated (e.g. the URI no longer
     * resolves) — callers distinguish that from "empty directory".
     */
    fun listSorted(dir: UniFile): Array<UniFile>? {
        val files = dir.listFiles(imageFilter) ?: return null
        files.sortWith(naturalComparator)
        return files
    }

    /**
     * Map 0-indexed page number -> position in [names] for directories
     * whose files use the download worker's zero-padded numeric naming
     * ("0001.jpg", "0002.png", ...; legacy 8-digit names too). Returns
     * null when any name doesn't parse as a pure positive number —
     * callers fall back to positional mapping (legacy title-named dirs,
     * arbitrary user content).
     *
     * Why: positional mapping silently SHIFTS every page after a gap (a
     * page the download worker failed on while its siblings completed),
     * so page N renders page N+1's image and the reported size is short.
     * Numeric mapping keeps real page numbers and turns gaps into
     * explicit per-page errors.
     */
    fun numericPageIndices(names: List<String>): Map<Int, Int>? {
        if (names.isEmpty()) return null
        val map = HashMap<Int, Int>(names.size)
        names.forEachIndexed { pos, name ->
            val stem = name.substringBeforeLast('.')
            if (stem.isEmpty() || stem.length > MAX_NUMERIC_STEM || !stem.all { it.isDigit() }) {
                return null
            }
            val page1 = stem.toInt()
            if (page1 < 1) return null
            if (map.put(page1 - 1, pos) != null) return null
        }
        return map
    }

    /** Longest digit run still safely parseable as Int (download worker writes 4). */
    private const val MAX_NUMERIC_STEM = 9

    /**
     * Page-space size of a directory: how many pages the reader exposes.
     *
     * - No numeric [map] (positional dir): the file count, exactly.
     * - Numeric map: `max(highestPageNumber + 1, expectedPageCount)` so a
     *   *trailing* gap (the archive is N pages but only the first M
     *   downloaded) still shows the missing tail as explicit error pages
     *   rather than silently ending early.
     *
     * Trade-off: if [expectedPageCount] is stale-HIGH for a *fully*
     * downloaded archive (server metadata's `pagecount` drifted above the
     * real `/files` length after a re-tag/re-upload), the extra tail slots
     * render as error pages until the next detail fetch corrects pagecount.
     * Accepted: surfacing a genuine missing tail is worth the rare phantom.
     */
    fun pageSpaceSize(map: Map<Int, Int>?, fileCount: Int, expectedPageCount: Int): Int {
        if (map == null) return fileCount
        val maxPage = (map.keys.maxOrNull() ?: -1) + 1
        return maxOf(maxPage, expectedPageCount)
    }

    /**
     * Resolve a warm-decode target for [pageIndex] (0-indexed page space).
     *
     * @return (page index to publish, file position to decode), or null when
     *   the dir uses numeric naming and the page is absent (a download gap) —
     *   the warm must be SKIPPED, never shifted onto a neighbouring file
     *   (decoding `files[pageIndex]` positionally is the RD-2 wrong-page bug).
     *   Legacy positional dirs clamp into range, where page == position.
     */
    fun warmTarget(names: List<String>, pageIndex: Int): Pair<Int, Int>? {
        if (names.isEmpty()) return null
        val map = numericPageIndices(names)
        if (map == null) {
            val pos = pageIndex.coerceIn(0, names.size - 1)
            return pos to pos
        }
        val pos = map[pageIndex] ?: return null
        return pageIndex to pos
    }

    /**
     * Decode a single image file. Hops through a temp file when the
     * source isn't a [FileInputStream] (e.g. SAF `content://` URIs)
     * so the native decoder can mmap it. Caller should run on the
     * decoder dispatcher.
     */
    fun decode(context: Context, file: UniFile): Image? {
        val inputStream = file.openInputStream()
        return if (inputStream is FileInputStream) {
            inputStream.use { Image.decode(it, false) }
        } else {
            val tmpFile = File.createTempFile(
                "dir_img_", ".tmp", context.applicationContext.cacheDir
            )
            try {
                inputStream.use { inp ->
                    FileOutputStream(tmpFile).use { fos -> inp.copyTo(fos) }
                }
                FileInputStream(tmpFile).use { fis -> Image.decode(fis, false) }
            } finally {
                if (!tmpFile.delete()) {
                    IOUtils.closeQuietly(null)
                }
            }
        }
    }
}
