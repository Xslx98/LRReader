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
