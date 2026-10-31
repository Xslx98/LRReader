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

import android.os.Looper
import java.io.File
import java.io.IOException

/** Process and device facts, ported from yorozuya's OSUtils. */
object OSUtils {

    private val MEMINFO_LINE = Regex("^([a-zA-Z]*):[ \t]*([0-9]*)[ \t]kB")

    @Volatile
    private var totalMemory = Long.MIN_VALUE

    /** @throws IllegalStateException off the main thread */
    @JvmStatic
    fun checkMainLoop() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "It is in not main loop!" }
    }

    @JvmStatic
    fun getAppAllocatedMemory(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    @JvmStatic
    fun getAppMaxMemory(): Long = Runtime.getRuntime().maxMemory()

    /** The device's RAM in bytes from /proc/meminfo (read once), or -1 when unknown. */
    @JvmStatic
    fun getTotalMemory(): Long {
        if (totalMemory == Long.MIN_VALUE) {
            totalMemory = readTotalMemory()
        }
        return totalMemory
    }

    private fun readTotalMemory(): Long {
        try {
            File("/proc/meminfo").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val match = MEMINFO_LINE.find(line) ?: continue
                    if (match.groupValues[1] == "MemTotal") {
                        val kb = match.groupValues[2].toLongOrNull() ?: return -1L
                        return kb * 1024
                    }
                }
            }
        } catch (_: IOException) {
            // Unknown.
        }
        return -1L
    }
}
