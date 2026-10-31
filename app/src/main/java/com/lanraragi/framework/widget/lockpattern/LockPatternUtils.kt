/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lanraragi.framework.widget.lockpattern

import java.nio.charset.Charset

/**
 * Utilities for the lock pattern and its settings.
 *
 * The serialized form is one byte `row * 3 + column` per cell, decoded and
 * encoded with the platform default charset exactly as the Java version's
 * `String.getBytes()` / `new String(bytes)` did, so stored patterns keep
 * matching.
 */
object LockPatternUtils {

    /**
     * Deserialize a pattern.
     * @param string The pattern serialized with [patternToString]
     * @return The pattern.
     */
    fun stringToPattern(string: String): List<LockPatternView.Cell> {
        val result = ArrayList<LockPatternView.Cell>()

        val bytes = string.toByteArray(Charset.defaultCharset())
        for (i in bytes.indices) {
            val b = bytes[i]
            result.add(LockPatternView.Cell.of(b / 3, b % 3))
        }
        return result
    }

    /**
     * Serialize a pattern.
     * @param pattern The pattern.
     * @return The pattern in string form.
     */
    fun patternToString(pattern: List<LockPatternView.Cell>?): String {
        if (pattern == null) {
            return ""
        }
        val patternSize = pattern.size

        val res = ByteArray(patternSize)
        for (i in 0 until patternSize) {
            val cell = pattern[i]
            res[i] = (cell.row * 3 + cell.column).toByte()
        }
        return String(res, Charset.defaultCharset())
    }

    fun isPatternVaild(pattern: String): Boolean {
        val bytes = pattern.toByteArray(Charset.defaultCharset())
        if (bytes.size <= 9 && bytes.size >= 2) {
            for (b in bytes) {
                if (b >= 9 || b < 0) {
                    return false
                }
            }
            return true
        } else {
            return false
        }
    }
}
