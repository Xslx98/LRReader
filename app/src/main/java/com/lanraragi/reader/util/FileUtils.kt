/*
 * Copyright 2015 Hippo Seven
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
package com.lanraragi.reader.util

import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/** File helpers ported from yorozuya's FileUtils (same results). */
object FileUtils {

    private val FORBIDDEN_FILENAME_CHARACTERS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    private val WHITE_SPACE = charArrayOf('\u0009', '\u0020', '\u00A0', '\u3000')
    private const val MAX_FILENAME_BYTES = 255

    /** True when [file] is (or could be created as) a regular file. */
    @JvmStatic
    fun ensureFile(file: File?): Boolean = file != null && (!file.exists() || file.isFile)

    /** True when [file] is a directory afterwards (created with missing parents). */
    @JvmStatic
    fun ensureDirectory(file: File?): Boolean {
        if (file == null) return false
        return if (file.exists()) file.isDirectory else file.mkdirs()
    }

    /**
     * Deletes [file], recursively for a directory. Each entry is renamed
     * before deletion so a half-deleted path is never reused while the
     * filesystem still holds it.
     */
    @JvmStatic
    fun delete(file: File?): Boolean {
        if (file == null) return false
        var success = deleteContent(file)
        val to = File(file.absolutePath + System.currentTimeMillis())
        success = if (file.renameTo(to)) to.delete() && success else file.delete() && success
        return success
    }

    /** Deletes everything inside the directory [file], keeping [file] itself. */
    @JvmStatic
    fun deleteContent(file: File?): Boolean {
        if (file == null) return false
        var success = true
        file.listFiles()?.forEach { success = delete(it) && success }
        return success
    }

    /** The text after the last '.', or null when [filename] has none. */
    @JvmStatic
    fun getExtensionFromFilename(filename: String?): String? {
        if (filename == null) return null
        val index = filename.lastIndexOf('.')
        return if (index != -1) filename.substring(index + 1) else null
    }

    /** "1.5 MiB" (binary units) or "1.5 MB" ([si] units). */
    @JvmStatic
    fun humanReadableByteCount(bytes: Long, si: Boolean): String {
        val unit = if (si) 1000 else 1024
        if (bytes < unit) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
        val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
        return String.format(Locale.US, "%.1f %sB", bytes / unit.toDouble().pow(exp), pre)
    }

    /**
     * [filename] without the characters filesystems forbid, cut to 255 UTF-8
     * bytes and trimmed of tabs, spaces, no-break and ideographic spaces.
     */
    @JvmStatic
    fun sanitizeFilename(filename: String): String {
        var name = filename.filterNot { it in FORBIDDEN_FILENAME_CHARACTERS }
        var byteCount = 0
        var length = 0
        while (length < name.length) {
            val ch = name[length]
            byteCount += when {
                ch.code <= 0x7F -> 1
                ch.code <= 0x7FF -> 2
                Character.isHighSurrogate(ch) -> { length++; 4 }
                else -> 3
            }
            if (byteCount > MAX_FILENAME_BYTES) {
                name = name.substring(0, length)
                break
            }
            length++
        }
        return name.trim { it in WHITE_SPACE }
    }
}
