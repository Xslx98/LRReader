/*
 * Copyright 2019 Hippo Seven
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
package com.hippo.ehviewer.client

import android.content.Context
import android.util.Base64
import android.util.Pair
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.Tag
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.util.ExceptionUtils
import com.hippo.util.IoThreadPoolExecutor
import com.hippo.util.TextUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.source
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.locks.ReentrantLock

class EhTagDatabase(private val name: String, source: okio.BufferedSource) {

    private val tags: ByteArray
    private val tagList: List<Tag>

    init {
        val totalBytes = source.readInt()
        tags = ByteArray(totalBytes)
        source.readFully(tags)
        val sourceString = String(tags.clone(), StandardCharsets.UTF_8)
        tagList = initTagList(sourceString)
    }

    fun getTranslation(tag: String): String? {
        return search(tags, tag.toByteArray(TextUrl.UTF_8!!))
    }

    private fun initTagList(sourceString: String): List<Tag> {
        return sourceString.split("\n").map { parseTag(it) }
    }

    private fun parseTag(source: String): Tag {
        val cArray = source.split("\r")
        if (cArray.size == 2) {
            val chinese = String(Base64.decode(cArray[1], Base64.DEFAULT), StandardCharsets.UTF_8)
            val eArray = cArray[0].split(":")
            val english = if (eArray.size == 2) {
                val key = eArray[0] + ":"
                val namespace = PREFIX_TO_NAMESPACE[key] ?: cArray[0]
                "$namespace:${eArray[1]}"
            } else {
                cArray[0]
            }
            return Tag(english, chinese)
        }
        return Tag(source, "null")
    }

    private fun search(tags: ByteArray, tag: ByteArray): String? {
        var low = 0
        var high = tags.size
        while (low < high) {
            var start = (low + high) / 2
            // Look for the starting '\n'
            while (start > -1 && tags[start] != '\n'.code.toByte()) {
                start--
            }
            start++

            // Look for the middle '\r'
            var middle = 1
            while (tags[start + middle] != '\r'.code.toByte()) {
                middle++
            }

            // Look for the ending '\n'
            var end = middle + 1
            while (tags[start + end] != '\n'.code.toByte()) {
                end++
            }

            var compare: Int
            var tagIndex = 0
            var curIndex = start

            while (true) {
                val tagByte = tag[tagIndex].toInt() and 0xff
                val curByte = tags[curIndex].toInt() and 0xff
                compare = tagByte - curByte
                if (compare != 0) break

                tagIndex++
                curIndex++
                if (tagIndex == tag.size && curIndex == start + middle) break
                if (tagIndex == tag.size) {
                    compare = -1
                    break
                }
                if (curIndex == start + middle) {
                    compare = 1
                    break
                }
            }

            when {
                compare < 0 -> high = start - 1
                compare > 0 -> low = start + end + 1
                else -> {
                    val bytes = Base64.decode(tags, start + middle + 1, end - middle - 1, Base64.DEFAULT)
                    return String(bytes, TextUrl.UTF_8!!)
                }
            }
        }
        return null
    }

    @JvmOverloads
    fun suggest(keyword: String, limit: Int = 40): List<Pair<String, String>> {
        return searchTag(tagList, keyword, limit)
    }

    private fun searchTag(tags: List<Tag>, keyword: String, limit: Int): List<Pair<String, String>> {
        val searchList = mutableListOf<Pair<String, String>>()
        for (tag in tags) {
            if (searchList.size >= limit) break
            if (tag.involve(keyword)) {
                searchList.add(Pair(tag.chinese, tag.english))
            }
        }
        return searchList
    }

    companion object {
        @JvmField
        val NAMESPACE_TO_PREFIX: Map<String, String> = mapOf(
            "rows" to "n:",
            "artist" to "a:",
            "cosplayer" to "cos:",
            "character" to "c:",
            "female" to "f:",
            "group" to "g:",
            "language" to "l:",
            "male" to "m:",
            "misc" to "",
            "mixed" to "x:",
            "other" to "o:",
            "parody" to "p:",
            "reclass" to "r:"
        )

        @JvmField
        val PREFIX_TO_NAMESPACE: Map<String, String> = mapOf(
            "n:" to "rows",
            "a:" to "artist",
            "cos:" to "cosplayer",
            "c:" to "character",
            "f:" to "female",
            "g:" to "group",
            "l:" to "language",
            "m:" to "male",
            "" to "misc",
            "x:" to "mixed",
            "o:" to "other",
            "p:" to "parody",
            "r:" to "reclass"
        )

        @Volatile
        private var instance: EhTagDatabase? = null

        // EH-LEGACY: multi-language lock not implemented, Chinese-only is sufficient
        private val lock = ReentrantLock()

        @JvmStatic
        fun getInstance(context: Context): EhTagDatabase? {
            return if (isPossible(context)) {
                instance
            } else {
                instance = null
                null
            }
        }

        @JvmStatic
        fun namespaceToPrefix(namespace: String): String? {
            val prefix = NAMESPACE_TO_PREFIX[namespace]
            if (prefix != null) return prefix
            if (PREFIX_TO_NAMESPACE.containsKey("$namespace:")) return namespace
            return null
        }

        @JvmStatic
        fun prefixToNamespace(prefix: String): String? {
            return PREFIX_TO_NAMESPACE[prefix]
        }

        private fun getMetadata(context: Context): Array<String>? {
            val metadata = context.resources.getStringArray(R.array.tag_translation_metadata)
            return if (metadata.size == 4) metadata else null
        }

        @JvmStatic
        fun isPossible(context: Context): Boolean {
            return getMetadata(context) != null
        }

        private fun getFileContent(file: File, length: Int): ByteArray? {
            return try {
                file.source().buffer().use { source ->
                    val content = ByteArray(length)
                    source.readFully(content)
                    content
                }
            } catch (_: java.io.IOException) {
                null
            }
        }

        private fun getFileSha1(file: File): ByteArray? {
            return try {
                FileInputStream(file).use { inputStream ->
                    val digest = MessageDigest.getInstance("SHA-1")
                    val buffer = ByteArray(4 * 1024)
                    var n: Int
                    while (inputStream.read(buffer).also { n = it } != -1) {
                        digest.update(buffer, 0, n)
                    }
                    digest.digest()
                }
            } catch (_: java.io.IOException) {
                null
            } catch (_: NoSuchAlgorithmException) {
                null
            }
        }

        private fun checkData(sha1File: File, dataFile: File): Boolean {
            val s1 = getFileContent(sha1File, 20) ?: return false
            val s2 = getFileSha1(dataFile) ?: return false
            return s1.contentEquals(s2)
        }

        private fun save(client: OkHttpClient, url: String, file: File): Boolean {
            val request = Request.Builder().url(url).build()
            val call = client.newCall(request)
            return try {
                call.execute().use { response ->
                    if (!response.isSuccessful) return false
                    val body = response.body ?: return false
                    body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            IOUtils.copy(inputStream, outputStream)
                        }
                    }
                    true
                }
            } catch (t: Throwable) {
                ExceptionUtils.throwIfFatal(t)
                Analytics.recordException(t)
                false
            }
        }

        @JvmStatic
        fun update(context: Context) {
            val urls = getMetadata(context)
            if (urls == null || urls.size != 4) {
                // Clear tags if it's not possible
                instance = null
                return
            }

            val sha1Name = urls[0]
            val sha1Url = urls[1]
            val dataName = urls[2]
            val dataUrl = urls[3]

            // Clear tags if name is different
            val tmp = instance
            if (tmp != null && tmp.name != dataName) {
                instance = null
            }

            IoThreadPoolExecutor.instance.execute {
                if (!lock.tryLock()) return@execute

                try {
                    val dir = AppConfig.getFilesDir("tag-translations") ?: return@execute

                    // Check current sha1 and current data
                    val sha1File = File(dir, sha1Name)
                    val dataFile = File(dir, dataName)
                    if (!checkData(sha1File, dataFile)) {
                        FileUtils.delete(sha1File)
                        FileUtils.delete(dataFile)
                    }

                    // Read current EhTagDatabase
                    if (instance == null && dataFile.exists()) {
                        try {
                            dataFile.source().buffer().use { source ->
                                instance = EhTagDatabase(dataName, source)
                            }
                        } catch (_: java.io.IOException) {
                            FileUtils.delete(sha1File)
                            FileUtils.delete(dataFile)
                        }
                    }

                    val client = ServiceRegistry.networkModule.okHttpClient

                    // Save new sha1
                    val tempSha1File = File(dir, "$sha1Name.tmp")
                    if (!save(client, sha1Url, tempSha1File)) {
                        FileUtils.delete(tempSha1File)
                        return@execute
                    }

                    // Check new sha1 and current data
                    if (checkData(tempSha1File, dataFile)) {
                        // The data is the same
                        FileUtils.delete(tempSha1File)
                        return@execute
                    }

                    // Save new data
                    val tempDataFile = File(dir, "$dataName.tmp")
                    if (!save(client, dataUrl, tempDataFile)) {
                        FileUtils.delete(tempDataFile)
                        return@execute
                    }

                    // Check new sha1 and new data
                    if (!checkData(tempSha1File, tempDataFile)) {
                        FileUtils.delete(tempSha1File)
                        FileUtils.delete(tempDataFile)
                        return@execute
                    }

                    // Replace current sha1 and current data with new sha1 and new data
                    FileUtils.delete(sha1File)
                    FileUtils.delete(dataFile)
                    tempSha1File.renameTo(sha1File)
                    tempDataFile.renameTo(dataFile)

                    // Read new EhTagDatabase
                    try {
                        dataFile.source().buffer().use { source ->
                            instance = EhTagDatabase(dataName, source)
                        }
                    } catch (_: java.io.IOException) {
                        // Ignore
                    }
                } finally {
                    lock.unlock()
                }
            }
        }
    }
}
