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

import android.text.TextUtils
import android.util.Log
import android.util.SparseArray
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.unifile.UniFile
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.NumberUtils
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

class SpiderInfo {

    @JvmField
    var startPage: Int = 0
    @JvmField
    var gid: Long = -1
    @JvmField
    var arcid: String? = null
    @JvmField
    var pages: Int = -1
    @JvmField
    var previewPages: Int = -1
    @JvmField
    var previewPerPage: Int = -1
    @JvmField
    var pTokenMap: SparseArray<String>? = null

    fun write(os: OutputStream) {
        var writer: OutputStreamWriter? = null
        try {
            writer = OutputStreamWriter(os)
            writer.write(VERSION_STR)
            writer.write(VERSION.toString())
            writer.write("\n")
            writer.write(String.format("%08x", maxOf(startPage, 0))) // Avoid negative
            writer.write("\n")
            writer.write(gid.toString())
            writer.write("\n")
            writer.write(arcid)
            writer.write("\n")
            writer.write("1")
            writer.write("\n")
            writer.write(previewPages.toString())
            writer.write("\n")
            writer.write(previewPerPage.toString())
            writer.write("\n")
            writer.write(pages.toString())
            writer.write("\n")
            val map = pTokenMap
            if (map != null) {
                for (i in 0 until map.size()) {
                    val key = map.keyAt(i)
                    val value = map.valueAt(i)
                    if (TOKEN_FAILED == value || TextUtils.isEmpty(value)) {
                        continue
                    }
                    writer.write(key.toString())
                    writer.write(" ")
                    writer.write(value)
                    writer.write("\n")
                }
            }
            writer.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to write spider info", e)
        } finally {
            IOUtils.closeQuietly(writer)
            IOUtils.closeQuietly(os)
        }
    }

    fun updateSpiderInfo(newInfo: SpiderInfo) {
        pages = newInfo.pages
        gid = newInfo.gid
        arcid = newInfo.arcid
    }

    companion object {
        private val TAG = SpiderInfo::class.java.simpleName

        private const val VERSION_STR = "VERSION"
        private const val VERSION = 2

        @JvmStatic
        val TOKEN_FAILED = "failed"

        @JvmStatic
        fun read(file: UniFile?): SpiderInfo? {
            if (file == null) return null
            var inputStream: InputStream? = null
            return try {
                inputStream = file.openInputStream()
                read(inputStream)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to read spider info from file", e)
                null
            } finally {
                IOUtils.closeQuietly(inputStream)
            }
        }

        private fun getStartPage(str: String?): Int {
            if (str == null) return 0
            var startPage = 0
            for (ch in str) {
                startPage *= 16
                when {
                    ch in '0'..'9' -> startPage += ch - '0'
                    ch in 'a'..'f' -> startPage += ch - 'a' + 10
                }
            }
            return maxOf(startPage, 0)
        }

        private fun getVersion(str: String?): Int {
            if (str == null) return -1
            return if (str.startsWith(VERSION_STR)) {
                NumberUtils.parseIntSafely(str.substring(VERSION_STR.length), -1)
            } else {
                1
            }
        }

        @JvmStatic
        @Suppress("InfiniteLoopStatement")
        fun read(inputStream: InputStream?): SpiderInfo? {
            if (inputStream == null) return null
            var spiderInfo: SpiderInfo? = null
            try {
                spiderInfo = SpiderInfo()
                // Get version
                var line = IOUtils.readAsciiLine(inputStream)
                val version = getVersion(line)
                if (version == VERSION) {
                    // Read next line
                    line = IOUtils.readAsciiLine(inputStream)
                } else if (version == 1) {
                    // pass
                } else {
                    // Invalid version
                    return null
                }
                // Start page
                spiderInfo.startPage = getStartPage(line)
                // Gid
                spiderInfo.gid = IOUtils.readAsciiLine(inputStream).toLong()
                // Token
                spiderInfo.arcid = IOUtils.readAsciiLine(inputStream)
                // Deprecated, mode, skip it
                IOUtils.readAsciiLine(inputStream)
                // Preview pages
                spiderInfo.previewPages = IOUtils.readAsciiLine(inputStream).toInt()
                // Preview per page
                line = IOUtils.readAsciiLine(inputStream)
                if (version == 1) {
                    // Skip it
                } else {
                    spiderInfo.previewPerPage = line.toInt()
                }
                // Pages
                spiderInfo.pages = IOUtils.readAsciiLine(inputStream).toInt()
                // Check pages
                if (spiderInfo.pages <= 0) {
                    return null
                }
                // PToken
                spiderInfo.pTokenMap = SparseArray(spiderInfo.pages)
                while (true) { // EOFException will raise
                    line = IOUtils.readAsciiLine(inputStream)
                    val pos = line.indexOf(" ")
                    if (pos > 0) {
                        val index = line.substring(0, pos).toInt()
                        val pToken = line.substring(pos + 1)
                        if (!TextUtils.isEmpty(pToken)) {
                            spiderInfo.pTokenMap!!.put(index, pToken)
                        }
                    } else {
                        Log.e(TAG, "Can't parse index and pToken, index = $pos")
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to parse spider info from input stream", e)
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Invalid number format in spider info", e)
            }

            return if (spiderInfo == null || spiderInfo.gid == -1L || spiderInfo.arcid == null ||
                spiderInfo.pages == -1 || spiderInfo.pTokenMap == null
            ) {
                null
            } else {
                spiderInfo
            }
        }

        /**
         * Read the persisted SpiderInfo for [arcid] from its download
         * directory. Returns null if the directory has no DB-tracked
         * dirname mapping, the dir doesn't exist on disk, the .ehviewer
         * file is missing/unreadable, or the parsed arcid doesn't match.
         *
         * Unlike the previous implementation, this does NOT call into
         * [SpiderDen.getGalleryDownloadDir] — that has a side effect of
         * creating a new dirname mapping when none exists, which is
         * inappropriate for a read-only lookup.
         */
        @JvmStatic
        suspend fun getSpiderInfo(arcid: String): SpiderInfo? {
            val baseDir = DownloadSettings.getDownloadLocation() ?: return null
            val dirname = ServiceRegistry.dataModule.downloadDbRepository
                .getDownloadDirname(arcid) ?: return null
            val dir = baseDir.subFile(dirname) ?: return null
            if (!dir.isDirectory) return null
            val file = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return null
            val spiderInfo = read(file) ?: return null
            return if (spiderInfo.arcid == arcid) spiderInfo else null
        }

    }
}
