package com.lanraragi.reader.tankoubon

/**
 * Chinese numerals as they appear in chapter titles: 一…九, 十, 百, 千,
 * 零/〇 and the colloquial 两. Handles positional runs (一二三 = 123),
 * unit forms (二十三, 一百零五, 两百, 一千零一) and the bare-unit heads
 * 十一 (11) / 十 (10). Anything outside the numeral alphabet is not a
 * numeral (null), so callers can probe a candidate substring safely.
 */
object ChineseNumeral {

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private val UNITS = mapOf('十' to 10, '百' to 100, '千' to 1000)

    /** Every character [parse] accepts, for building a regex character class. */
    val ALPHABET: String = (DIGITS.keys + UNITS.keys).joinToString("")

    private fun isNumeralChar(c: Char): Boolean = c in DIGITS || c in UNITS

    /** The value of [text], or null when it is not entirely a numeral. */
    fun parse(text: String): Int? {
        if (text.isEmpty() || !text.all(::isNumeralChar)) return null
        if (text.none { it in UNITS }) {
            return text.fold(0) { acc, c -> acc * 10 + DIGITS.getValue(c) }
        }
        var total = 0
        var current = 0
        for (c in text) {
            val digit = DIGITS[c]
            if (digit != null) {
                current = digit
            } else {
                val unit = UNITS.getValue(c)
                total += (if (current == 0) 1 else current) * unit
                current = 0
            }
        }
        return total + current
    }
}
