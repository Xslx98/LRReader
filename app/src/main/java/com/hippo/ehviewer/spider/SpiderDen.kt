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

import android.content.Context
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.settings.ReadingSettings
import com.hippo.io.UniFileInputStreamPipe
import com.hippo.io.UniFileOutputStreamPipe
import com.hippo.streampipe.InputStreamPipe
import com.hippo.streampipe.OutputStreamPipe
import com.hippo.unifile.FilenameFilter
import com.hippo.unifile.UniFile
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.MathUtils
import com.hippo.lib.yorozuya.Utilities
import java.io.File
import java.io.IOException
import java.util.Locale

class SpiderDen(galleryInfo: GalleryInfo) {

    @JvmField
    var mDownloadDir: UniFile? = null
        private set

    @Volatile
    private var mMode: Int = SpiderQueen.MODE_READ

    private var mGid: Long = galleryInfo.gid

    /**
     * Initializes the download directory by resolving it from DB/filesystem.
     * Must be called after construction from a coroutine context.
     */
    suspend fun initDownloadDir(galleryInfo: GalleryInfo) {
        mDownloadDir = getGalleryDownloadDir(galleryInfo)
    }

    fun setMGid(mGid: Long) {
        this.mGid = mGid
    }

    fun setMode(@SpiderQueen.Mode mode: Int) {
        mMode = mode
        if (mode == SpiderQueen.MODE_DOWNLOAD) {
            ensureDownloadDir()
        }
    }

    private fun ensureDownloadDir(): Boolean {
        return mDownloadDir != null && mDownloadDir.ensureDir()
    }

    fun isReady(): Boolean {
        return when (mMode) {
            SpiderQueen.MODE_READ -> sCache != null
            SpiderQueen.MODE_DOWNLOAD -> mDownloadDir != null && mDownloadDir.isDirectory
            else -> false
        }
    }

    fun getDownloadDir(): UniFile? {
        return if (mDownloadDir != null && mDownloadDir.isDirectory) mDownloadDir else null
    }

    fun getDownloadDirName(): UniFile? {
        return mDownloadDir
    }

    private fun containInCache(index: Int): Boolean {
        val cache = sCache ?: return false
        val key = EhCacheKeyFactory.getImageKey(mGid, index)
        return cache.contain(key)
    }

    private fun containInDownloadDir(index: Int): Boolean {
        val dir = getDownloadDir() ?: return false
        return findImageFile(dir, index) != null
    }

    private fun fixExtension(extension: String): String {
        return if (Utilities.contain(GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS, extension)) {
            extension
        } else {
            GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[0]
        }
    }

    private fun copyFromCacheToDownloadDir(index: Int): Boolean {
        val cache = sCache ?: return false
        val dir = getDownloadDir() ?: return false
        val key = EhCacheKeyFactory.getImageKey(mGid, index)
        val pipe = cache.getInputStreamPipe(key) ?: return false

        var os: java.io.OutputStream? = null
        try {
            // Get extension
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            pipe.obtain()
            try {
                BitmapFactory.decodeStream(pipe.open(), null, options)
            } finally {
                pipe.close()
            }
            var extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(options.outMimeType)
                ?: return false
            extension = ".$extension"
            // Fix extension
            extension = fixExtension(extension)
            // Copy from cache to download dir
            val file = dir.createFile(generateImageFilename(index, extension)) ?: return false
            os = file.openOutputStream()
            try {
                IOUtils.copy(pipe.open(), os)
            } finally {
                pipe.close()
            }
            return true
        } catch (e: IOException) {
            return false
        } finally {
            IOUtils.closeQuietly(os)
            pipe.release()
        }
    }

    fun contain(index: Int): Boolean {
        return when (mMode) {
            SpiderQueen.MODE_READ -> containInCache(index) || containInDownloadDir(index)
            SpiderQueen.MODE_DOWNLOAD -> containInDownloadDir(index) || copyFromCacheToDownloadDir(index)
            else -> false
        }
    }

    private fun removeFromCache(index: Int): Boolean {
        val cache = sCache ?: return false
        val key = EhCacheKeyFactory.getImageKey(mGid, index)
        return cache.remove(key)
    }

    private fun removeFromDownloadDir(index: Int): Boolean {
        val dir = getDownloadDir() ?: return false
        var result = false
        for (ext in GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            val filename = generateImageFilename(index, ext)
            val file = dir.subFile(filename)
            if (file != null) {
                result = result or file.delete()
            }
        }
        return result
    }

    fun remove(index: Int): Boolean {
        var result = removeFromCache(index)
        result = result or removeFromDownloadDir(index)
        return result
    }

    private fun openCacheOutputStreamPipe(index: Int): OutputStreamPipe? {
        val cache = sCache ?: return null
        val key = EhCacheKeyFactory.getImageKey(mGid, index)
        return cache.getOutputStreamPipe(key)
    }

    /**
     * @param extension without dot
     */
    private fun openDownloadOutputStreamPipe(index: Int, extension: String?): OutputStreamPipe? {
        val dir = getDownloadDir() ?: return null
        val fixedExtension = if (extension == null || !extension.contains(".")) {
            fixExtension(".$extension")
        } else {
            fixExtension(extension)
        }
        val file = dir.createFile(generateImageFilename(index, fixedExtension)) ?: return null
        return UniFileOutputStreamPipe(file)
    }

    fun openOutputStreamPipe(index: Int, extension: String?): OutputStreamPipe? {
        return when (mMode) {
            SpiderQueen.MODE_READ -> {
                // Return the download pipe if the gallery has been downloaded
                openDownloadOutputStreamPipe(index, extension)
                    ?: openCacheOutputStreamPipe(index)
            }
            SpiderQueen.MODE_DOWNLOAD -> openDownloadOutputStreamPipe(index, extension)
            else -> null
        }
    }

    private fun openCacheInputStreamPipe(index: Int): InputStreamPipe? {
        val cache = sCache ?: return null
        val key = EhCacheKeyFactory.getImageKey(mGid, index)
        return cache.getInputStreamPipe(key)
    }

    fun openDownloadInputStreamPipe(index: Int): InputStreamPipe? {
        val dir = getDownloadDir() ?: return null
        for (i in 0 until 2) {
            val file = findImageFile(dir, index)
            if (file != null) {
                return UniFileInputStreamPipe(file)
            } else if (!copyFromCacheToDownloadDir(index)) {
                return null
            }
        }
        return null
    }

    fun openInputStreamPipe(index: Int): InputStreamPipe? {
        return when (mMode) {
            SpiderQueen.MODE_READ -> {
                openDownloadInputStreamPipe(index) ?: openCacheInputStreamPipe(index)
            }
            SpiderQueen.MODE_DOWNLOAD -> openDownloadInputStreamPipe(index)
            else -> null
        }
    }

    class StartWithFilenameFilter(private val mPrefix: String) : FilenameFilter {
        override fun accept(dir: UniFile, filename: String): Boolean {
            return filename.startsWith(mPrefix)
        }
    }

    companion object {
        @Volatile
        private var sCache: SimpleDiskCache? = null

        @JvmStatic
        @Synchronized
        fun initialize(context: Context) {
            if (sCache != null) return
            sCache = SimpleDiskCache(
                File(context.cacheDir, "image"),
                MathUtils.clamp(ReadingSettings.getReadCacheSize(), 40, 640) * 1024 * 1024
            )
        }

        /**
         * Resolves the download directory for the given gallery.
         *
         * This is a suspend function that calls [EhDB] methods directly.
         * All callers must be in a coroutine context.
         */
        @JvmStatic
        suspend fun getGalleryDownloadDir(galleryInfo: GalleryInfo): UniFile? {
            val dir = DownloadSettings.getDownloadLocation() ?: return null

            // Read from DB
            var dirname = EhDB.getDownloadDirnameAsync(galleryInfo.gid)
            if (dirname != null) {
                // Some dirname may be invalid in some version
                dirname = FileUtils.sanitizeFilename(dirname)
                EhDB.putDownloadDirnameAsync(galleryInfo.gid, dirname)
            }

            // Find it
            if (dirname == null) {
                try {
                    val files = dir.listFiles(StartWithFilenameFilter("${galleryInfo.gid}-"))
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
                            EhDB.putDownloadDirnameAsync(galleryInfo.gid, dirname)
                        }
                    }
                } catch (e: Exception) {
                    // Failed to list files, maybe storage is unavailable or permission lost
                    // Continue to create new directory
                    android.util.Log.w("SpiderDen", "Failed to list files in download directory", e)
                }
            }

            // Create it
            if (dirname == null) {
                dirname = FileUtils.sanitizeFilename("${galleryInfo.gid}-${EhUtils.getSuitableTitle(galleryInfo)}")
                EhDB.putDownloadDirnameAsync(galleryInfo.gid, dirname)
            }

            return dir.subFile(dirname)
        }

        /**
         * @param extension with dot
         */
        @JvmStatic
        fun generateImageFilename(index: Int, extension: String): String {
            return String.format(Locale.US, "%08d%s", index + 1, extension)
        }

        @JvmStatic
        fun findImageFile(dir: UniFile, index: Int): UniFile? {
            for (extension in GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                val filename = generateImageFilename(index, extension)
                val file = dir.findFile(filename)
                if (file != null) {
                    return file
                }
            }
            return null
        }
    }
}
