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
package com.lanraragi.reader.client

import com.lanraragi.reader.BuildConfig
import android.content.Context
import android.util.Base64
import android.util.Pair
import com.lanraragi.reader.Analytics
import com.lanraragi.reader.AppConfig
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.framework.lib.yorozuya.FileUtils
import com.lanraragi.framework.lib.yorozuya.IOUtils
import com.lanraragi.framework.util.ExceptionUtils
import android.util.Log
import com.lanraragi.framework.util.TextUrl
import com.lanraragi.reader.client.api.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.source
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

class TagTranslationDatabase(private val name: String, source: okio.BufferedSource) {

    /**
     * Lightweight internal tag entry replacing the deleted legacy [com.lanraragi.reader.client.data.Tag] class.
     */
    private data class TagEntry(val english: String?, val chinese: String?) {
        fun involve(chars: String): Boolean {
            if (english != null && english.contains(chars)) return true
            return chinese != null && chinese.contains(chars)
        }
    }

    private val tags: ByteArray
    private val tagList: List<TagEntry>

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

    /**
     * Translate one LANraragi tag. [namespace] is the namespace as the server
     * spells it (`artist`, `series`, `misc`, … or an EhViewer one-letter
     * shorthand); a blank or `misc` namespace probes the dataset namespaces in
     * [BARE_TAG_PROBE_ORDER] because the dataset has no namespace-less keys.
     * Namespaces the dataset does not know (`date_added`, `source`,
     * user-defined) never translate. Matching is case-insensitive.
     */
    fun translateTag(namespace: String?, value: String): String? {
        val key = value.trim().lowercase()
        if (key.isEmpty()) return null
        val ns = namespace?.trim()?.lowercase().orEmpty()
        if (ns.isEmpty() || ns == BARE_NAMESPACE) {
            for (prefix in BARE_TAG_PROBE_ORDER) {
                getTranslation(prefix + key)?.let { return it }
            }
            return null
        }
        val prefix = datasetPrefixFor(ns) ?: return null
        return getTranslation(prefix + key)
    }

    /** Translate a namespace name via the dataset's `n:` rows, or null when it has none. */
    fun translateNamespace(namespace: String): String? {
        val canonical = canonicalNamespace(namespace.trim().lowercase()) ?: return null
        return getTranslation("n:$canonical")
    }

    private fun initTagList(sourceString: String): List<TagEntry> {
        // `n:` rows name namespaces, not tags; suggesting them would offer
        // search terms like "rows:artist" that match nothing.
        return sourceString.split("\n")
            .filter { it.isNotEmpty() && !it.startsWith(NAMESPACE_ROW_PREFIX) }
            .map { parseTag(it) }
    }

    private fun parseTag(source: String): TagEntry {
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
            return TagEntry(english, chinese)
        }
        return TagEntry(source, "null")
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

    private fun searchTag(tags: List<TagEntry>, keyword: String, limit: Int): List<Pair<String, String>> {
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
        private val TAG = TagTranslationDatabase::class.java.simpleName

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
            "reclass" to "r:",
            "location" to "loc:"
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
            "r:" to "reclass",
            "loc:" to "location"
        )

        private const val NAMESPACE_ROW_PREFIX = "n:"

        /** LANraragi's namespace for tags that carry none (see TagParser). */
        private const val BARE_NAMESPACE = "misc"

        /** LANraragi spellings that differ from the dataset's EhViewer namespaces. */
        private val NAMESPACE_ALIASES: Map<String, String> = mapOf(
            "series" to "parody",
            "misc" to "other",
            "cos" to "cosplayer",
            "loc" to "location"
        )

        /**
         * Aliases that only apply to tag values: an LRR `category:doujinshi`
         * value is an EhViewer reclass, but labelling the group "重新分类"
         * (reclass) would misname it, so its header stays untranslated.
         */
        private val VALUE_ONLY_ALIASES: Map<String, String> = mapOf(
            "category" to "reclass"
        )

        /**
         * Probe order for namespace-less tags: `other` (where EhViewer's old
         * `misc` tags went) first, then the namespaces most tags live in.
         */
        private val BARE_TAG_PROBE_ORDER: List<String> = listOf(
            "o:", "a:", "g:", "p:", "c:", "f:", "m:", "l:", "x:", "r:", "cos:", "loc:"
        )

        /** Dataset namespace for a lower-cased LRR namespace, or null when the dataset has none. */
        internal fun canonicalNamespace(namespace: String): String? {
            NAMESPACE_ALIASES[namespace]?.let { return it }
            if (NAMESPACE_TO_PREFIX.containsKey(namespace)) return namespace
            return PREFIX_TO_NAMESPACE["$namespace:"]
        }

        /** Dataset key prefix (`a:`, `p:`, …) for a lower-cased LRR namespace, or null. */
        internal fun datasetPrefixFor(namespace: String): String? {
            val canonical = VALUE_ONLY_ALIASES[namespace] ?: canonicalNamespace(namespace) ?: return null
            return NAMESPACE_TO_PREFIX[canonical]
        }

        @Volatile
        private var instance: TagTranslationDatabase? = null

        // EH-LEGACY: multi-language lock not implemented, Chinese-only is sufficient
        // Single-flight guard. Deliberately NOT a ReentrantLock: save() now
        // suspends in Call.await() (NET-3) and may resume on a different IO
        // thread, where a thread-confined unlock() throws
        // IllegalMonitorStateException.
        private val updateInFlight = AtomicBoolean(false)

        // Network-check throttle (24h persisted success TTL + 15min in-memory
        // attempt TTL). Lazily bound to the application context's prefs.
        @Volatile
        private var updateThrottle: TagDbUpdateThrottle? = null

        private fun getUpdateThrottle(context: Context): TagDbUpdateThrottle {
            return updateThrottle ?: TagDbUpdateThrottle(
                context.applicationContext.getSharedPreferences(
                    TagDbUpdateThrottle.PREFS_NAME, Context.MODE_PRIVATE,
                ),
            ).also { updateThrottle = it }
        }

        @JvmStatic
        fun getInstance(context: Context): TagTranslationDatabase? {
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
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Failed to read tag database file content", e)
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
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Failed to compute SHA-1 for tag database file", e)
                null
            } catch (e: NoSuchAlgorithmException) {
                Log.w(TAG, "SHA-1 algorithm not available", e)
                null
            }
        }

        private fun checkData(sha1File: File, dataFile: File): Boolean {
            val s1 = getFileContent(sha1File, 20) ?: return false
            val s2 = getFileSha1(dataFile) ?: return false
            return s1.contentEquals(s2)
        }

        private suspend fun save(client: OkHttpClient, url: String, file: File): Boolean {
            val request = Request.Builder().url(url).build()
            val call = client.newCall(request)
            return try {
                call.await().use { response ->
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
                if (t is CancellationException) throw t
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

            val throttle = getUpdateThrottle(context)

            ServiceRegistry.coroutineModule.ioScope.launch {
                if (!updateInFlight.compareAndSet(false, true)) return@launch

                try {
                    val dir = AppConfig.getFilesDir("tag-translations") ?: return@launch

                    // Check current sha1 and current data
                    val sha1File = File(dir, sha1Name)
                    val dataFile = File(dir, dataName)
                    if (!checkData(sha1File, dataFile)) {
                        FileUtils.delete(sha1File)
                        FileUtils.delete(dataFile)
                    }

                    // Read current TagTranslationDatabase
                    if (instance == null && dataFile.exists()) {
                        try {
                            dataFile.source().buffer().use { source ->
                                instance = TagTranslationDatabase(dataName, source)
                            }
                        } catch (e: java.io.IOException) {
                            Log.w(TAG, "Failed to read existing tag database", e)
                            FileUtils.delete(sha1File)
                            FileUtils.delete(dataFile)
                        }
                    }

                    // Network check is throttled: local load above always runs
                    // (memory state must be rebuilt after process death), but
                    // GitHub is consulted at most once per TTL window.
                    if (!throttle.shouldAttempt()) return@launch
                    throttle.recordAttempt()

                    // The translation DB files are multi-MB; the shared
                    // large-file client has no call cap so a slow link can't
                    // abort the download mid-stream (see INetworkModule).
                    val client = ServiceRegistry.networkModule.largeFileClient

                    // Save new sha1
                    val tempSha1File = File(dir, "$sha1Name.tmp")
                    if (!save(client, sha1Url, tempSha1File)) {
                        FileUtils.delete(tempSha1File)
                        return@launch
                    }

                    // Check new sha1 and current data
                    if (checkData(tempSha1File, dataFile)) {
                        // The data is the same
                        FileUtils.delete(tempSha1File)
                        throttle.recordSuccess()
                        return@launch
                    }

                    // Save new data
                    val tempDataFile = File(dir, "$dataName.tmp")
                    if (!save(client, dataUrl, tempDataFile)) {
                        FileUtils.delete(tempDataFile)
                        return@launch
                    }

                    // Check new sha1 and new data
                    if (!checkData(tempSha1File, tempDataFile)) {
                        FileUtils.delete(tempSha1File)
                        FileUtils.delete(tempDataFile)
                        return@launch
                    }

                    // Replace current sha1 and current data with new sha1 and new data
                    FileUtils.delete(sha1File)
                    FileUtils.delete(dataFile)
                    tempSha1File.renameTo(sha1File)
                    tempDataFile.renameTo(dataFile)

                    // Read new TagTranslationDatabase
                    try {
                        dataFile.source().buffer().use { source ->
                            instance = TagTranslationDatabase(dataName, source)
                        }
                        throttle.recordSuccess()
                    } catch (e: java.io.IOException) {
                        // Throwable-arg Log.w survives R8's strip; gate it.
                        if (BuildConfig.DEBUG) Log.w(TAG, "Failed to read updated tag database", e)
                    }
                } finally {
                    updateInFlight.set(false)
                }
            }
        }
    }
}
