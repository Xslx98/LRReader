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

import androidx.annotation.VisibleForTesting
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
import com.lanraragi.reader.settings.AppearanceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /**
     * Parsed rows for [suggest], built on first use (off the main thread,
     * see SearchBar) instead of at load: translation lookups only need the
     * byte array, and many sessions never type a search (audit C36).
     */
    private val tagList: List<TagEntry> by lazy { initTagList(String(tags, StandardCharsets.UTF_8)) }

    init {
        // The length prefix comes from a third-party file (audit C26 /
        // SEC-11): a negative or huge value must fail as a corrupt dataset,
        // not as NegativeArraySizeException or an OOM on every launch.
        val totalBytes = source.readInt()
        if (totalBytes !in 0..MAX_DATASET_BYTES) {
            throw java.io.IOException("Tag dataset length out of range: $totalBytes")
        }
        tags = ByteArray(totalBytes)
        source.readFully(tags)
        // The SHA-1 check only proves the file matches its own hash file;
        // the lookups below assume `key\rvalue\n` rows (audit SEC-01).
        if (!isWellFormed(tags)) {
            throw java.io.IOException("Tag dataset is malformed")
        }
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
        val chinese = if (cArray.size == 2) decodeOrNull(cArray[1]) else null
        if (chinese != null) {
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

        /** Upper bound for the dataset body; the real file is a few MB. */
        internal const val MAX_DATASET_BYTES = 32 * 1024 * 1024

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
        @VisibleForTesting
        internal var instance: TagTranslationDatabase? = null

        // EH-LEGACY: multi-language lock not implemented, Chinese-only is sufficient
        // Single-flight guard. Deliberately NOT a ReentrantLock: save() now
        // suspends in Call.await() (NET-3) and may resume on a different IO
        // thread, where a thread-confined unlock() throws
        // IllegalMonitorStateException.
        private val updateInFlight = AtomicBoolean(false)

        // Network-check throttle (24h persisted success TTL + 15min in-memory
        // attempt TTL). Lazily bound to the application context's prefs.
        @Volatile
        @VisibleForTesting
        internal var updateThrottle: TagDbUpdateThrottle? = null

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

        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D

        /**
         * Whether [body] is a non-empty run of `key\rvalue\n` rows: every row
         * ends with '\n' and holds exactly one '\r' with a non-empty key and
         * value on either side. Sort order and base64 are not checked here.
         */
        @VisibleForTesting
        internal fun isWellFormed(body: ByteArray): Boolean {
            if (body.isEmpty() || body[body.size - 1] != LF) return false
            var rowStart = 0
            var separator = -1
            for (i in body.indices) {
                when (body[i]) {
                    CR -> {
                        if (separator != -1 || i == rowStart) return false
                        separator = i
                    }
                    LF -> {
                        if (separator == -1 || separator == i - 1) return false
                        rowStart = i + 1
                        separator = -1
                    }
                }
            }
            return true
        }

        /**
         * Binary search for [tag] in the sorted `key\rbase64\n` rows of
         * [tags]. Bounds-checked so that a malformed dataset yields null
         * (no translation) rather than an exception on the UI thread
         * (audit SEC-01).
         */
        @VisibleForTesting
        internal fun search(tags: ByteArray, tag: ByteArray): String? {
            if (tag.isEmpty()) return null
            var low = 0
            var high = tags.size
            while (low < high) {
                // Row that contains the midpoint: [start, separator) is the
                // key, (separator, end) the value, tags[end] the '\n'.
                var start = (low + high) / 2
                while (start > 0 && tags[start - 1] != LF) {
                    start--
                }
                var separator = start
                while (separator < tags.size && tags[separator] != CR && tags[separator] != LF) {
                    separator++
                }
                if (separator == start || separator >= tags.size || tags[separator] != CR) return null
                var end = separator + 1
                while (end < tags.size && tags[end] != LF) {
                    end++
                }
                if (end >= tags.size) return null

                val compare = compareKey(tag, tags, start, separator)
                when {
                    compare < 0 -> high = start
                    compare > 0 -> low = end + 1
                    else -> return decodeValue(tags, separator + 1, end - separator - 1)
                }
            }
            return null
        }

        /** Unsigned byte-wise comparison of [tag] with `tags[from, to)`. */
        private fun compareKey(tag: ByteArray, tags: ByteArray, from: Int, to: Int): Int {
            val keyLength = to - from
            val common = minOf(tag.size, keyLength)
            for (i in 0 until common) {
                val diff = (tag[i].toInt() and 0xff) - (tags[from + i].toInt() and 0xff)
                if (diff != 0) return diff
            }
            return tag.size - keyLength
        }

        private fun decodeOrNull(value: String): String? {
            return try {
                String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Bad base64 in tag dataset row", e)
                null
            }
        }

        private fun decodeValue(tags: ByteArray, offset: Int, length: Int): String? {
            return try {
                String(Base64.decode(tags, offset, length, Base64.DEFAULT), StandardCharsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Bad base64 in tag dataset row", e)
                null
            }
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

        /**
         * Whether the dataset may be fetched from its third-party host
         * (raw.githubusercontent.com). Only while translations are shown,
         * which [AppearanceSettings.getShowTagTranslations] already limits to
         * a Chinese system locale (audit C26, ruling R10). Test seam.
         */
        @VisibleForTesting
        internal var remoteFetchAllowed: () -> Boolean = { AppearanceSettings.getShowTagTranslations() }

        /**
         * Remote half of [update]: fetch the sha1, then the data when it
         * changed, verify and swap the files in. Runs only behind the
         * [remoteFetchAllowed] gate and the throttle.
         */
        private suspend fun fetchRemote(dir: File, urls: Array<String>, throttle: TagDbUpdateThrottle) {
            val sha1Name = urls[0]
            val sha1Url = urls[1]
            val dataName = urls[2]
            val dataUrl = urls[3]
            val sha1File = File(dir, sha1Name)
            val dataFile = File(dir, dataName)

            // The translation DB files are multi-MB; the shared
            // large-file client has no call cap so a slow link can't
            // abort the download mid-stream (see INetworkModule).
            val client = ServiceRegistry.networkModule.largeFileClient

            // Save new sha1
            val tempSha1File = File(dir, "$sha1Name.tmp")
            if (!save(client, sha1Url, tempSha1File)) {
                FileUtils.delete(tempSha1File)
                return
        }

        // Check new sha1 and current data
        if (checkData(tempSha1File, dataFile)) {
            // The data is the same
            FileUtils.delete(tempSha1File)
            throttle.recordSuccess()
            return
        }

        // Save new data
        val tempDataFile = File(dir, "$dataName.tmp")
        if (!save(client, dataUrl, tempDataFile)) {
            FileUtils.delete(tempDataFile)
            return
        }

        // Check new sha1 and new data
        if (!checkData(tempSha1File, tempDataFile)) {
            FileUtils.delete(tempSha1File)
            FileUtils.delete(tempDataFile)
            return
        }

        // Parse the new data before it replaces anything: a dataset that
        // fails the structure check is discarded and the current copy (if
        // any) stays active (audit SEC-01).
        val newDatabase = load(dataName, tempDataFile)
        if (newDatabase == null) {
            FileUtils.delete(tempSha1File)
            FileUtils.delete(tempDataFile)
            return
        }

        // Replace current sha1 and current data with new sha1 and new data
        FileUtils.delete(sha1File)
        FileUtils.delete(dataFile)
        tempSha1File.renameTo(sha1File)
        tempDataFile.renameTo(dataFile)

        instance = newDatabase
        throttle.recordSuccess()
        }

        /** Reads and validates the dataset in [file], or null when it is unreadable or malformed. */
        @VisibleForTesting
        internal fun load(name: String, file: File): TagTranslationDatabase? {
            return try {
                file.source().buffer().use { source -> TagTranslationDatabase(name, source) }
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Rejected tag database file", e)
                null
            }
        }

        /**
         * Loads the local dataset and, when [remoteFetchAllowed] and the
         * throttle permit, checks the remote copy. Returns the background job.
         */
        @JvmStatic
        fun update(context: Context): Job? {
            val urls = getMetadata(context)
            if (urls == null || urls.size != 4) {
                // Clear tags if it's not possible
                instance = null
                return null
            }

            val sha1Name = urls[0]
            val dataName = urls[2]

            // Clear tags if name is different
            val tmp = instance
            if (tmp != null && tmp.name != dataName) {
                instance = null
            }

            val throttle = getUpdateThrottle(context)

            return ServiceRegistry.coroutineModule.ioScope.launch {
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
                    // A malformed copy is deleted so the remote check below
                    // can replace it; until then there are no translations.
                    if (instance == null && dataFile.exists()) {
                        val local = load(dataName, dataFile)
                        if (local == null) {
                            FileUtils.delete(sha1File)
                            FileUtils.delete(dataFile)
                        } else {
                            instance = local
                        }
                    }

                    // Network check is gated and throttled: local load above
                    // always runs (memory state must be rebuilt after process
                    // death), but GitHub is consulted only while translations
                    // are shown, and at most once per TTL window.
                    if (!remoteFetchAllowed() || !throttle.shouldAttempt()) return@launch
                    throttle.recordAttempt()

                    fetchRemote(dir, urls, throttle)
                } finally {
                    updateInFlight.set(false)
                }
            }
        }
    }
}
