/*
 * Copyright 2018 Hippo Seven
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
package com.hippo.ehviewer

import android.content.ContentValues
import android.content.Context
import android.util.Pair
import com.hippo.database.MSQLiteBuilder
import com.hippo.util.SqlUtils
import java.net.InetAddress
import java.net.UnknownHostException

class Hosts(context: Context, name: String) {

    private val helper = MSQLiteBuilder()
        .version(VERSION_1)
        .createTable(TABLE_HOSTS)
        .insertColumn(TABLE_HOSTS, COLUMN_HOST, String::class.java)
        .insertColumn(TABLE_HOSTS, COLUMN_IP, String::class.java)
        .build(context, name, DB_VERSION)

    private val db = helper.writableDatabase

    /**
     * Gets a InetAddress with the host.
     */
    fun get(host: String?): InetAddress? {
        if (!isValidHost(host)) return null

        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_HOSTS WHERE $COLUMN_HOST = ?;",
            arrayOf(host)
        )
        return cursor.use {
            if (it.moveToNext()) {
                val ip = SqlUtils.getString(it, COLUMN_IP, null)
                toInetAddress(host, ip)
            } else {
                null
            }
        }
    }

    /**
     * Gets a List<InetAddress> with the host.
     */
    fun getList(host: String?): List<InetAddress>? {
        if (!isValidHost(host)) return null

        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_HOSTS WHERE $COLUMN_HOST = ?;",
            arrayOf(host)
        )
        val list = mutableListOf<InetAddress>()
        cursor.use {
            while (it.moveToNext()) {
                val ip = SqlUtils.getString(it, COLUMN_IP, null)
                val inetAddress = toInetAddress(host, ip)
                if (inetAddress != null) {
                    list.add(inetAddress)
                }
            }
        }
        return list
    }

    private fun contains(host: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_HOSTS WHERE $COLUMN_HOST = ?;",
            arrayOf(host)
        )
        return cursor.use { it.moveToNext() }
    }

    /**
     * Puts the host-ip pair into this hosts.
     */
    fun put(host: String?, ip: String?): Boolean {
        if (!isValidHost(host) || !isValidIp(ip)) return false

        val values = ContentValues().apply {
            put(COLUMN_HOST, host)
            put(COLUMN_IP, ip)
        }

        if (contains(host!!)) {
            db.update(TABLE_HOSTS, values, "$COLUMN_HOST = ?", arrayOf(host))
        } else {
            db.insert(TABLE_HOSTS, null, values)
        }

        return true
    }

    /**
     * Deletes the entry with the host.
     */
    fun delete(host: String?) {
        if (host == null) return
        db.delete(TABLE_HOSTS, "$COLUMN_HOST = ?", arrayOf(host))
    }

    /**
     * Get all data from this host.
     */
    fun getAll(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val cursor = db.rawQuery("SELECT * FROM $TABLE_HOSTS;", null)
        cursor.use {
            while (it.moveToNext()) {
                val host = SqlUtils.getString(it, COLUMN_HOST, null)
                val ip = SqlUtils.getString(it, COLUMN_IP, null)
                val inetAddress = toInetAddress(host, ip)
                if (inetAddress == null) continue
                result.add(Pair(host, ip))
            }
        }
        return result
    }

    companion object {
        private const val VERSION_1 = 1
        private const val TABLE_HOSTS = "HOSTS"
        private const val COLUMN_HOST = "HOST"
        private const val COLUMN_IP = "IP"
        private const val DB_VERSION = VERSION_1

        @JvmStatic
        fun toInetAddress(host: String?, ip: String?): InetAddress? {
            if (!isValidHost(host)) return null
            if (ip == null) return null

            var bytes = parseV4(ip)
            if (bytes == null) bytes = parseV6(ip)
            if (bytes == null) return null

            return try {
                InetAddress.getByAddress(host, bytes)
            } catch (e: UnknownHostException) {
                null
            }
        }

        /**
         * Returns true if the host is valid.
         */
        @JvmStatic
        fun isValidHost(host: String?): Boolean {
            if (host == null) return false
            if (host.length > 253) return false

            var labelLength = 0
            for (ch in host) {
                if (ch == '.') {
                    if (labelLength < 1 || labelLength > 63) return false
                    labelLength = 0
                } else {
                    labelLength++
                }
                if (ch !in 'a'..'z' && ch !in '0'..'9' && ch != '-' && ch != '.') {
                    return false
                }
            }

            return labelLength in 1..63
        }

        /**
         * Returns true if the ip is valid.
         */
        @JvmStatic
        fun isValidIp(ip: String?): Boolean {
            return ip != null && (parseV4(ip) != null || parseV6(ip) != null)
        }

        // org.xbill.DNS.Address.parseV4
        private fun parseV4(s: String): ByteArray? {
            val values = ByteArray(4)
            var currentOctet = 0
            var currentValue = 0
            var numDigits = 0

            for (c in s) {
                if (c in '0'..'9') {
                    if (numDigits == 3) return null
                    if (numDigits > 0 && currentValue == 0) return null
                    numDigits++
                    currentValue = currentValue * 10 + (c - '0')
                    if (currentValue > 255) return null
                } else if (c == '.') {
                    if (currentOctet == 3) return null
                    if (numDigits == 0) return null
                    values[currentOctet++] = currentValue.toByte()
                    currentValue = 0
                    numDigits = 0
                } else {
                    return null
                }
            }

            if (currentOctet != 3) return null
            if (numDigits == 0) return null
            values[currentOctet] = currentValue.toByte()
            return values
        }

        // org.xbill.DNS.Address.parseV6
        @Suppress("ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
        private fun parseV6(s: String): ByteArray? {
            var range = -1
            val data = ByteArray(16)
            val tokens = s.split(":", limit = -1).toTypedArray()

            var first = 0
            var last = tokens.size - 1

            if (tokens[0].isEmpty()) {
                if (last - first > 0 && tokens[1].isEmpty()) {
                    first++
                } else {
                    return null
                }
            }

            if (tokens[last].isEmpty()) {
                if (last - first > 0 && tokens[last - 1].isEmpty()) {
                    last--
                } else {
                    return null
                }
            }

            if (last - first + 1 > 8) return null

            var j = 0
            var i = first
            while (i <= last) {
                if (tokens[i].isEmpty()) {
                    if (range >= 0) return null
                    range = j
                    i++
                    continue
                }

                if (tokens[i].indexOf('.') >= 0) {
                    if (i < last) return null
                    if (i > 6) return null
                    val v4addr = parseV4(tokens[i]) ?: return null
                    for (k in 0..3) {
                        data[j++] = v4addr[k]
                    }
                    break
                }

                try {
                    for (k in tokens[i].indices) {
                        if (Character.digit(tokens[i][k], 16) < 0) return null
                    }
                    val x = tokens[i].toInt(16)
                    if (x > 0xFFFF || x < 0) return null
                    data[j++] = (x ushr 8).toByte()
                    data[j++] = (x and 0xFF).toByte()
                } catch (e: NumberFormatException) {
                    return null
                }

                i++
            }

            if (j < 16 && range < 0) return null

            if (range >= 0) {
                val empty = 16 - j
                System.arraycopy(data, range, data, range + empty, j - range)
                for (idx in range until range + empty) {
                    data[idx] = 0
                }
            }

            return data
        }
    }
}
