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
import com.lanraragi.reader.download.DownloadDirNaming
import com.lanraragi.reader.settings.DownloadSettings
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.framework.lib.yorozuya.FileUtils
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

    /**
     * Resolves the download directory for the given archive.
     *
     * Suspend because it calls [com.lanraragi.reader.dao.DownloadDbRepository].
     * Callers must be in a coroutine context.
     *
     * Resolution chain for the download root (post-v26):
     *  1. [rootUriOverride] when the caller already holds a
     *     [com.lanraragi.reader.dao.DownloadInfo.downloadRootUri] in memory.
     *  2. The URI persisted on the archive's `ARCHIVE_LOCAL_STATE` row
     *     (`DOWNLOAD_ROOT_URI`) — set when the archive was first added
     *     to the download subsystem, so it survives later changes to
     *     [DownloadSettings.getDownloadLocation].
     *  3. The current [DownloadSettings.getDownloadLocation] as the
     *     final fallback for legacy rows that have not been backfilled
     *     and for arcids with no download row at all.
     *
     * @param arcid LANraragi archive id (the directory's primary key)
     * @param title display title — used only when the directory has to be
     *   created (it becomes the directory name, see DownloadDirNaming)
     * @param rootUriOverride explicit override skipping the DB lookup;
     *   pass `info.downloadRootUri` when the caller already has a
     *   `DownloadInfo` in scope.
     */
    @JvmStatic
    suspend fun getGalleryDownloadDir(
        arcid: String,
        title: String?,
        rootUriOverride: String? = null,
    ): UniFile? {
        val downloadDbRepo = ServiceRegistry.dataModule.downloadDbRepository
        val storedUri = rootUriOverride ?: downloadDbRepo.getDownloadRootUri(arcid)
        val dir = resolveRootDir(storedUri) ?: return null

        // Read from DB
        var dirname = downloadDbRepo.getDownloadDirname(arcid)
        if (dirname != null) {
            // Some dirname may be invalid in some version. This runs on every
            // thumbnail bind (a new ThumbDataContainer per bind, fast scroll),
            // so only write back when sanitizing actually changed the value —
            // an unconditional putDownloadDirname churned the DB with an
            // identical value on every bind.
            val sanitized = FileUtils.sanitizeFilename(dirname)
            if (sanitized != dirname) {
                downloadDbRepo.putDownloadDirname(arcid, sanitized)
            }
            dirname = sanitized
        }

        // Create it — the sanitised title, de-duplicated against siblings
        // (DownloadDirNaming); the DB pointer is the only arcid → dir link.
        if (dirname == null) {
            dirname = DownloadDirNaming.uniqueName(DownloadDirNaming.baseName(arcid, title)) { name ->
                dir.findFile(name) != null
            }
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
