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

/**
 * The math helpers the GL reader, scenes and widgets use, ported from
 * yorozuya's MathUtils (same results, including the order of the clamp
 * checks when min > max).
 */
object MathUtils {

    private const val DEG_TO_RAD = 3.1415926f / 180.0f

    @JvmStatic
    fun radians(degrees: Float): Float = degrees * DEG_TO_RAD

    @JvmStatic
    fun lerp(start: Int, stop: Int, amount: Float): Int = start + ((stop - start) * amount).toInt()

    @JvmStatic
    fun lerp(start: Float, stop: Float, amount: Float): Float = start + (stop - start) * amount

    /** Where [value] lies between [start] and [stop]; 1 when they are equal. */
    @JvmStatic
    fun delerp(start: Int, stop: Int, value: Int): Float =
        if (stop == start) 1.0f else (value - start).toFloat() / (stop - start).toFloat()

    /** Where [value] lies between [start] and [stop]; 1 when they are equal. */
    @JvmStatic
    fun delerp(start: Float, stop: Float, value: Float): Float =
        if (stop == start) 1.0f else (value - start) / (stop - start)

    @JvmStatic
    fun clamp(x: Int, min: Int, max: Int): Int = if (x > max) max else if (x < min) min else x

    @JvmStatic
    fun clamp(x: Float, min: Float, max: Float): Float = if (x > max) max else if (x < min) min else x

    @JvmStatic
    fun clamp(x: Long, min: Long, max: Long): Long = if (x > max) max else if (x < min) min else x

    /**
     * The next power of two, or [n] itself when it is one.
     *
     * @throws IllegalArgumentException when [n] <= 0 or the result overflows
     */
    @JvmStatic
    fun nextPowerOf2(n: Int): Int {
        require(n > 0 && n <= 1 shl 30) { "n is invalid: $n" }
        var v = n - 1
        v = v or (v shr 16)
        v = v or (v shr 8)
        v = v or (v shr 4)
        v = v or (v shr 2)
        v = v or (v shr 1)
        return v + 1
    }

    /** [x] modulo [y], shifted into [0, y) for a negative [x]. */
    @JvmStatic
    fun positiveModulo(x: Float, y: Float): Float {
        val result = x % y
        return if (x < 0) result + y else result
    }
}
