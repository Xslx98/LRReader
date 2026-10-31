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
 * Invariant checks that fail with an [AssertionError] (an [Error], so a
 * `catch (Exception)` never swallows a broken invariant), ported from
 * yorozuya's AssertUtils.
 */
object AssertUtils {

    @JvmStatic
    fun assertEquals(expected: Int, actual: Int) {
        assertEquals("Should be $expected, but it is $actual", expected, actual)
    }

    @JvmStatic
    fun assertEquals(message: String, expected: Int, actual: Int) {
        if (expected != actual) throw AssertionError(message)
    }

    @JvmStatic
    fun assertNotNull(obj: Any?) {
        assertNotNull("Should not be null", obj)
    }

    @JvmStatic
    fun assertNotNull(message: String, obj: Any?) {
        if (obj == null) throw AssertionError(message)
    }

    @JvmStatic
    fun assertNull(obj: Any?) {
        assertNull("Should be null", obj)
    }

    @JvmStatic
    fun assertNull(message: String, obj: Any?) {
        if (obj != null) throw AssertionError(message)
    }

    @JvmStatic
    fun assertTrue(cond: Boolean) {
        assertTrue("Condition has to be true.", cond)
    }

    @JvmStatic
    fun assertTrue(message: String, cond: Boolean) {
        if (!cond) throw AssertionError(message)
    }
}
