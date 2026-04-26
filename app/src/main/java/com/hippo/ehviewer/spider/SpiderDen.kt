/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.spider

import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.unifile.FilenameFilter
import com.hippo.unifile.UniFile
import com.hippo.lib.yorozuya.FileUtils
import java.util.Locale

/**
 * Helpers for resolving an archive's on-disk download directory and the
 * filename layout LRReader uses for downloaded pages.
 *
 * The original EhViewer SpiderDen instance class (image cache + per-archive
 * state) has been removed: LANraragi's image flow goes through
 * [com.hippo.ehviewer.gallery.LRRGalleryProvider] /
 * [com.hippo.ehviewer.download.LRRDownloadWorker] and never created a
 * SpiderDen instance. Only the directory + filename helpers survive.
 */
object SpiderDen {

    /**
     * Resolves the download directory for the given archive.
     *
     * Suspend because it calls [com.hippo.ehviewer.dao.DownloadDbRepository].
     * Callers must be in a coroutine context.
     *
     * @param arcid LANraragi archive id (the directory's primary key)
     * @param title display title — used only when the directory has to be
     *   created (it becomes part of the directory name)
     * @param legacyGid optional EH-era gid; used as a fallback prefix when
     *   listing directories so that very old installs (pre-W34-1) whose
     *   on-disk dirs are gid-prefixed can still be matched. Pass `null` if
     *   the caller has no gid value (e.g. arcid-only contexts).
     */
    @JvmStatic
    suspend fun getGalleryDownloadDir(
        arcid: String,
        title: String?,
        legacyGid: Long? = null,
    ): UniFile? {
        val dir = DownloadSettings.getDownloadLocation() ?: return null

        // Read from DB
        val downloadDbRepo = ServiceRegistry.dataModule.downloadDbRepository
        var dirname = downloadDbRepo.getDownloadDirname(arcid)
        if (dirname != null) {
            // Some dirname may be invalid in some version
            dirname = FileUtils.sanitizeFilename(dirname)
            downloadDbRepo.putDownloadDirname(arcid, dirname)
        }

        // Find it by arcid prefix (new format), then fall back to gid prefix (legacy)
        if (dirname == null) {
            try {
                // Try arcid-prefixed directory first (new format)
                var files = dir.listFiles(StartWithFilenameFilter("$arcid-"))
                // Fall back to gid-prefixed directory (legacy installs)
                if ((files == null || files.isEmpty()) && legacyGid != null && legacyGid != 0L) {
                    files = dir.listFiles(StartWithFilenameFilter("$legacyGid-"))
                }
                if (files != null) {
                    // Get max-length-name dir
                    var maxLength = -1
                    for (file in files) {
                        if (file.isDirectory) {
                            val name = file.name ?: continue
                            val length = name.length
                            if (length > maxLength) {
                                maxLength = length
                                dirname = name
                            }
                        }
                    }
                    if (dirname != null) {
                        downloadDbRepo.putDownloadDirname(arcid, dirname)
                    }
                }
            } catch (e: Exception) {
                // Failed to list files, maybe storage is unavailable or permission lost
                // Continue to create new directory
                android.util.Log.w("SpiderDen", "Failed to list files in download directory", e)
            }
        }

        // Create it — use arcid as prefix for unique directory names
        if (dirname == null) {
            dirname = FileUtils.sanitizeFilename("$arcid-$title")
            downloadDbRepo.putDownloadDirname(arcid, dirname)
        }

        return dir.subFile(dirname)
    }

    /**
     * @param extension with dot (e.g. ".jpg")
     */
    @JvmStatic
    fun generateImageFilename(index: Int, extension: String): String =
        String.format(Locale.US, "%08d%s", index + 1, extension)

    @JvmStatic
    fun findImageFile(dir: UniFile, index: Int): UniFile? {
        for (extension in GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            val filename = generateImageFilename(index, extension)
            val file = dir.findFile(filename)
            if (file != null) return file
        }
        return null
    }

    private class StartWithFilenameFilter(private val prefix: String) : FilenameFilter {
        override fun accept(dir: UniFile, filename: String): Boolean = filename.startsWith(prefix)
    }
}
