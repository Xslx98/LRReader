package com.lanraragi.reader.tankoubon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseNumeralTest {

    private fun check(expected: Int, text: String) =
        assertEquals("parse($text)", expected, ChineseNumeral.parse(text))

    @Test
    fun singleDigits() {
        check(0, "零")
        check(0, "〇")
        check(1, "一")
        check(2, "二")
        check(2, "两")
        check(9, "九")
    }

    @Test
    fun tens() {
        check(10, "十")
        check(11, "十一")
        check(20, "二十")
        check(23, "二十三")
        check(99, "九十九")
    }

    @Test
    fun hundredsAndThousands() {
        check(100, "一百")
        check(105, "一百零五")
        check(123, "一百二十三")
        check(200, "两百")
        check(210, "二百一十")
        check(1000, "一千")
        check(1001, "一千零一")
        check(2345, "两千三百四十五")
    }

    @Test
    fun positionalRunsWithoutUnits() {
        check(123, "一二三")
        check(105, "一〇五")
    }

    @Test
    fun rejectsNonNumerals() {
        assertNull(ChineseNumeral.parse(""))
        assertNull(ChineseNumeral.parse("abc"))
        assertNull(ChineseNumeral.parse("第一"))
        assertNull(ChineseNumeral.parse("12"))
        assertNull(ChineseNumeral.parse("一话"))
    }

    @Test
    fun alphabetMatchesParser() {
        assertTrue(ChineseNumeral.ALPHABET.all(ChineseNumeral::isNumeralChar))
        assertFalse(ChineseNumeral.isNumeralChar('话'))
        assertFalse(ChineseNumeral.isNumeralChar('1'))
    }
}
