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
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.exception.ParseException
import com.hippo.streampipe.OutputStreamPipe
import com.hippo.unifile.UniFile
import com.hippo.util.ExceptionUtils
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
    var token: String? = null
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
            writer.write(token)
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
            // Ignore
        } finally {
            IOUtils.closeQuietly(writer)
            IOUtils.closeQuietly(os)
        }
    }

    fun updateSpiderInfo(newInfo: SpiderInfo) {
        pages = newInfo.pages
        gid = newInfo.gid
        token = newInfo.token
        pTokenMap = newInfo.pTokenMap
        previewPerPage = newInfo.previewPerPage
        previewPages = newInfo.previewPages
    }

    @Synchronized
    fun writeNewSpiderInfoToLocal(spiderDen: SpiderDen, context: android.content.Context?) {
        val downloadDir = spiderDen.getDownloadDir()
        if (downloadDir != null) {
            val file = downloadDir.createFile(SpiderQueen.SPIDER_INFO_FILENAME)
            try {
                write(file.openOutputStream())
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
                // Ignore
            }
            // Read from cache
            val pipe: OutputStreamPipe = ServiceRegistry.dataModule
                .spiderInfoCache
                .getOutputStreamPipe(gid.toString())
            try {
                pipe.obtain()
                write(pipe.open())
            } catch (e: IOException) {
                // Ignore
            } finally {
                pipe.close()
                pipe.release()
            }
        }
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
                spiderInfo.token = IOUtils.readAsciiLine(inputStream)
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
                // Ignore
            } catch (e: NumberFormatException) {
                // Ignore
            }

            return if (spiderInfo == null || spiderInfo.gid == -1L || spiderInfo.token == null ||
                spiderInfo.pages == -1 || spiderInfo.pTokenMap == null
            ) {
                null
            } else {
                spiderInfo
            }
        }

        @JvmStatic
        suspend fun getSpiderInfo(info: GalleryInfo): SpiderInfo? {
            val mDownloadDir = SpiderDen.getGalleryDownloadDir(info)
            if (mDownloadDir != null && mDownloadDir.isDirectory) {
                val file = mDownloadDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
                val spiderInfo = read(file)
                if (spiderInfo != null && spiderInfo.gid == info.gid &&
                    spiderInfo.token == info.token
                ) {
                    return spiderInfo
                }
            }
            return null
        }

        @JvmStatic
        fun getSpiderInfo(info: GalleryDetail): SpiderInfo? {
            try {
                val spiderInfo = SpiderInfo()
                spiderInfo.gid = info.gid
                spiderInfo.token = info.token
                spiderInfo.pages = info.SpiderInfoPages
                spiderInfo.pTokenMap = SparseArray(spiderInfo.pages)
                readPreviews(info, 0, spiderInfo)
                return spiderInfo
            } catch (e: ParseException) {
                Analytics.recordException(e)
            }
            return null
        }

        @Throws(ParseException::class)
        private fun readPreviews(info: GalleryDetail, index: Int, spiderInfo: SpiderInfo) {
            spiderInfo.pages = info.SpiderInfoPages
            spiderInfo.previewPages = info.SpiderInfoPreviewPages
            val previewSet = info.SpiderInfoPreviewSet

            if (previewSet != null && previewSet.size() > 0) {
                spiderInfo.previewPerPage = if (index == 0) {
                    previewSet.size()
                } else {
                    previewSet.getPosition(0) / index
                }
            }

            // LANraragi: E-Hentai page URL parsing removed (pTokenMap not populated)
        }
    }
}
