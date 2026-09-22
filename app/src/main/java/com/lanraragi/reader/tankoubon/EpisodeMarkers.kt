package com.lanraragi.reader.tankoubon

/**
 * The episode / volume / chapter markers a tank member title may carry,
 * shared by member sorting ([TankTitleSortKey]) and name suggestion
 * ([TankNameSuggester]) so the two never drift. One place to teach the
 * app a new pattern.
 */
object EpisodeMarkers {

    private const val UNITS = "话話回卷章集期"
    private const val RANGE = "[-~～–—至]"
    private val NUMERAL = "[0-9]+|[${ChineseNumeral.ALPHABET}]+"

    /** 第N话 / 第N / N话, Chinese or Arabic numerals, optional range. Groups 1/2 = the leading numeral. */
    val CJK_EPISODE = Regex(
        "第\\s*($NUMERAL)(?:\\s*$RANGE\\s*(?:$NUMERAL))?\\s*[$UNITS]?" +
            "|($NUMERAL)(?:\\s*$RANGE\\s*(?:$NUMERAL))?\\s*[$UNITS]"
    )

    /** Vol 3 / Ch.03 / Chapter 3 / Ep3 / Episode 3 / #3. Group 1 = the number. */
    val LATIN_EPISODE = Regex(
        "(?:\\b(?:vol|volume|ch|chapter|ep|episode)\\.?\\s*|#\\s*)([0-9]+)",
        RegexOption.IGNORE_CASE,
    )

    /** 上/中/下 as a marker (bracketed / spaced / at the end / with 篇編部), 前篇/后篇. Groups 1/2 = the marker. */
    val SUB_ORDER = Regex(
        "(?:^|[\\s（(\\[【・·\\-—])([上中下])(?:[篇編部])?(?=$|[\\s）)\\]】・·\\-—])" +
            "|([前后後])[篇編]"
    )

    const val EXTRA_MARKER = "番外"

    /** Fullwidth digits / letters / `＃` / `．` / space → ASCII so width never matters. */
    fun normalizeWidth(s: String): String = buildString(s.length) {
        for (c in s) {
            append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    in 'Ａ'..'Ｚ' -> 'A' + (c - 'Ａ')
                    in 'ａ'..'ｚ' -> 'a' + (c - 'ａ')
                    '＃' -> '#'
                    '．' -> '.'
                    '　' -> ' '
                    else -> c
                }
            )
        }
    }

    /** Arabic or Chinese numeral → Int. */
    fun numeralValue(raw: String): Int? = raw.toIntOrNull() ?: ChineseNumeral.parse(raw)
}
