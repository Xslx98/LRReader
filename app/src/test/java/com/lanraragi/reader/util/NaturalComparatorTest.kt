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

package com.lanraragi.reader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalComparatorTest {

    private val comparator = NaturalComparator()

    @Test
    fun testOrder() {
        assertOrder("1", "2")
        assertOrder("2", "11")
        assertOrder("2", "00011")

        assertOrder("a2", "a11")
        assertOrder("a2a", "a11a")
    }

    @Test
    fun testEquals() {
        assertSame("2", "2")
        assertSame("11", "11")
        assertSame("a11", "a11")
        assertSame("a2", "a2")
        assertSame("a2a", "a2a")
    }

    @Test
    fun testEmpty() {
        assertOrder("", "1")
        assertSame("", "")
    }

    @Test
    fun testNull() {
        assertSame(null, null)
        assertOrder(null, "1")
    }

    @Test
    fun testBigNumber() {
        assertOrder("1", "22222222222222222222222222222222222222222222222222222222222222222222222")
    }

    @Test
    fun testLeadingZero() {
        assertOrder("000000000000000001", "1")
        assertOrder("000000000000000001", "0001")
        assertOrder("00000000000000000", "0")
        assertOrder("00000000000000000", "000")
    }

    private fun assertOrder(s1: String?, s2: String?) {
        assertTrue(comparator.compare(s1, s2) < 0)
        assertTrue(comparator.compare(s2, s1) > 0)
    }

    /** Both orders compare equal. */
    private fun assertSame(s1: String?, s2: String?) {
        assertEquals(0, comparator.compare(s1, s2))
        assertEquals(0, comparator.compare(s2, s1))
    }
}
