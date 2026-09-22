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

package com.lanraragi.reader.spider

import androidx.core.net.toUri
import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.Settings
import com.lanraragi.reader.gallery.GalleryProvider2
import com.lanraragi.reader.download.DownloadDirAllocator
import com.lanraragi.reader.download.DownloadDirNaming
import com.lanraragi.reader.settings.DownloadSettings
import com.lanraragi.framework.unifile.UniFile
import java.util.Locale

/**
 * Helpers for resolving an archive's on-disk download directory and the
 * filename layout LRReader uses for downloaded pages.
 *
 * The original EhViewer SpiderDen instance class (image cache + per-archive
 * state) has been removed: LANraragi's image flow goes through
 * [com.lanraragi.reader.gallery.LRRGalleryProvider] /
 * [com.lanraragi.reader.download.LRRDownloadWorker] and never created a
 * SpiderDen instance. Only the directory + filename helpers survive.
 */
object SpiderDen {

    private const val TAG = "SpiderDen"

    /** Process-wide: its mutex is what makes pointer allocation atomic. */
    private val allocator by lazy {
        DownloadDirAllocator(ServiceRegistry.dataModule.downloadDbRepository, ::resolveRootDir)
    }

    /**
     * The download directory [arcid]'s `DOWNLOAD_DIRNAME` pointer names, or
     * null when the archive has no pointer. Lookup only — never creates a
     * pointer or a directory; the directory may not exist.
     *
     * Root resolution (post-v26): [rootUriOverride] (the caller's
     * in-memory `downloadRootUri`), else the row's persisted
     * `DOWNLOAD_ROOT_URI`, else the current download location. Delete
     * paths must pass [rootUriOverride]: the row may already be gone.
     */
    @JvmStatic
    suspend fun findGalleryDownloadDir(arcid: String, rootUriOverride: String? = null): UniFile? =
        allocator.find(arcid, rootUriOverride)

    /**
     * The download directory for [arcid], creating it (named after
     * [title], see [DownloadDirNaming]) together with its pointer when
     * there is none yet. Only callers that are about to write pages — the
     * download worker and the reader's write-through for tracked
     * downloads — may call this.
     */
    @JvmStatic
    suspend fun allocateGalleryDownloadDir(
        arcid: String,
        title: String?,
        rootUriOverride: String? = null,
    ): UniFile? = allocator.allocate(arcid, title, rootUriOverride)

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

    /**
     * Build a [UniFile] for the supplied tree URI, falling back to
     * [DownloadSettings.getDownloadLocation] when [storedUri] is null,
     * malformed, or no longer accessible (SAF permission revoked,
     * directory deleted). This is the per-archive root resolver used
     * by the download read paths so a setting change after-the-fact
     * doesn't orphan a previously-downloaded archive — and a SAF
     * revoke surfaces as a `null` from the entire helper rather than
     * a crash.
     */
    internal fun resolveRootDir(storedUri: String?): UniFile? {
        if (!storedUri.isNullOrEmpty()) {
            try {
                val parsed = storedUri.toUri()
                val file = UniFile.fromUri(Settings.getContext(), parsed)
                if (file != null) return file
                Log.w(TAG, "Stored DOWNLOAD_ROOT_URI no longer resolvable, falling back to current setting")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse stored DOWNLOAD_ROOT_URI, falling back to current setting", e)
            }
        }
        return DownloadSettings.getDownloadLocation()
    }
}
