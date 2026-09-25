package com.lanraragi.reader.tankoubon

/**
 * Episode-aware sort key for tankoubon member titles (spec 2026-09-21 §2).
 *
 * Three layers, one entry point ([parse]):
 * 1. **Episode**: the number attached to 话/回/卷/章/集/期 (`第N…` or `N话`),
 *    or a `Vol` / `Ch` / `Chapter` / `Ep` / `Episode` / `#` prefix; Arabic
 *    or Chinese numerals; a range `A-B` / `A~B` / `A至B` takes `A`. Marker
 *    words never compare, so 「第一回」 ≡ 「第1话」 ≡ 「第01话」.
 *    Sub-order inside one episode: 上 < 中 < 下, 前篇 < 后篇.
 * 2. **First number**: no episode marker → the first digit run in the title.
 * 3. **Natural** comparison of the whole title (digit runs numeric, case-
 *    and width-insensitive) as the final tie-break.
 *
 * 「番外」 sorts after every regular member, ordered among themselves by
 * the same rules.
 */
data class TankTitleSortKey(
    val extra: Boolean,
    val episode: Int?,
    val subOrder: Int,
    val firstNumber: Int?,
    val natural: String,
) {

    /** Episode when marked, else the first number — the layer-1/2 value. */
    val primary: Int? get() = episode ?: firstNumber

    companion object {

        // Marker regexes live in EpisodeMarkers (shared with TankNameSuggester).
        private val CJK_EPISODE get() = EpisodeMarkers.CJK_EPISODE
        private val LATIN_EPISODE get() = EpisodeMarkers.LATIN_EPISODE
        private val SUB_ORDER get() = EpisodeMarkers.SUB_ORDER
        private const val EXTRA_MARKER = EpisodeMarkers.EXTRA_MARKER

        private val FIRST_NUMBER = Regex("[0-9]+")

        private val NATURAL_TOKEN = Regex("[0-9]+|[^0-9]+")

        val comparator: Comparator<TankTitleSortKey> = Comparator { a, b ->
            when {
                a.extra != b.extra -> if (a.extra) 1 else -1
                a.primary != b.primary -> compareNullable(a.primary, b.primary)
                a.subOrder != b.subOrder -> a.subOrder.compareTo(b.subOrder)
                else -> compareNatural(a.natural, b.natural)
            }
        }

        fun parse(title: String): TankTitleSortKey {
            val text = normalizeWidth(title)
            return TankTitleSortKey(
                extra = EXTRA_MARKER in text,
                episode = findEpisode(text),
                subOrder = findSubOrder(text),
                firstNumber = FIRST_NUMBER.find(text)?.value?.toIntOrNull(),
                natural = text.lowercase(),
            )
        }

        private fun findEpisode(text: String): Int? {
            CJK_EPISODE.find(text)?.let { m ->
                val raw = m.groups[1]?.value ?: m.groups[2]?.value
                raw?.let(::numeralValue)?.let { return it }
            }
            return LATIN_EPISODE.find(text)?.groups?.get(1)?.value?.toIntOrNull()
        }

        private fun numeralValue(raw: String): Int? = EpisodeMarkers.numeralValue(raw)

        private fun findSubOrder(text: String): Int {
            val m = SUB_ORDER.find(text) ?: return 0
            return when (m.groups[1]?.value ?: m.groups[2]?.value) {
                "中" -> 1
                "下", "后", "後" -> 2
                else -> 0
            }
        }

        private fun normalizeWidth(s: String): String = EpisodeMarkers.normalizeWidth(s)

        private fun compareNullable(a: Int?, b: Int?): Int = when {
            a == null -> 1
            b == null -> -1
            else -> a.compareTo(b)
        }

        /** Natural order: digit runs compare numerically, text runs lexically. */
        fun compareNatural(a: String, b: String): Int {
            val ta = NATURAL_TOKEN.findAll(a).map { it.value }.toList()
            val tb = NATURAL_TOKEN.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(ta.size, tb.size)) {
                val c = compareToken(ta[i], tb[i])
                if (c != 0) return c
            }
            return ta.size.compareTo(tb.size)
        }

        private fun compareToken(x: String, y: String): Int {
            if (!x[0].isDigit() || !y[0].isDigit()) return x.compareTo(y)
            val xs = x.trimStart('0')
            val ys = y.trimStart('0')
            return if (xs.length != ys.length) xs.length.compareTo(ys.length) else xs.compareTo(ys)
        }
    }
}
