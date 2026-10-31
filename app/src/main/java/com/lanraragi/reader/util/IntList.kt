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
 * A growable list of primitive ints (no boxing), ported from yorozuya's
 * IntList. The GL canvases hand [internalArray] straight to GL calls, so
 * the backing array is exposed instead of copied.
 */
class IntList @JvmOverloads constructor(capacity: Int = 0) {

    private var array = IntArray(capacity)
    private var size = 0

    fun add(value: Int) {
        val s = size
        if (s == array.size) {
            array = array.copyOf(s + if (s < MIN_CAPACITY_INCREMENT / 2) MIN_CAPACITY_INCREMENT else s shr 1)
        }
        array[s] = value
        size = s + 1
    }

    /** Inserts [value] at [location], shifting the later values up. */
    fun add(location: Int, value: Int) {
        val s = size
        if (location > s || location < 0) throw IndexOutOfBoundsException("Invalid index $location, size is $s")
        if (s == array.size) {
            array = array.copyOf(s + if (s < MIN_CAPACITY_INCREMENT / 2) MIN_CAPACITY_INCREMENT else s shr 1)
        }
        System.arraycopy(array, location, array, location + 1, s - location)
        array[location] = value
        size = s + 1
    }

    fun get(location: Int): Int {
        checkIndex(location)
        return array[location]
    }

    /** Replaces the value at [location] and returns the old one. */
    fun set(location: Int, value: Int): Int {
        checkIndex(location)
        val result = array[location]
        array[location] = value
        return result
    }

    /** Removes the value at [location] and returns it. */
    fun removeAt(location: Int): Int {
        checkIndex(location)
        val result = array[location]
        System.arraycopy(array, location + 1, array, location, size - location - 1)
        size--
        return result
    }

    fun clear() {
        size = 0
    }

    fun size(): Int = size

    /** The backing array; only the first [size] values are meaningful. */
    fun getInternalArray(): IntArray = array

    /** A copy of the values, e.g. for a saved-state Bundle. */
    fun toArray(): IntArray = array.copyOf(size)

    private fun checkIndex(location: Int) {
        if (location >= size) throw IndexOutOfBoundsException("Invalid index $location, size is $size")
    }

    companion object {
        private const val MIN_CAPACITY_INCREMENT = 12

        /** A list holding [values] (empty for null), e.g. from a saved-state Bundle. */
        @JvmStatic
        fun of(values: IntArray?): IntList = IntList().apply {
            if (values != null) {
                array = values.copyOf()
                size = values.size
            }
        }
    }
}
