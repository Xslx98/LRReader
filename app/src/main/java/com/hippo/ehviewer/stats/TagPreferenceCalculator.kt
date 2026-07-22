package com.hippo.ehviewer.stats

import com.hippo.ehviewer.dao.HistoryStatsRow

/**
 * Pure tag-preference derivation (issue #19) over the history archives'
 * tag snapshots: each archive contributes 1 count per tag (rule kept
 * deliberately explainable — no progress weighting), structural namespaces
 * are excluded (metadata, not taste — triage decision, including language),
 * and results group into the three decided top lists.
 */
object TagPreferenceCalculator {

    data class TagCount(val tag: String, val count: Int)

    data class TagPreference(
        /** Top artist: tags. */
        val artists: List<TagCount>,
        /** Top series: + parody: tags, merged. */
        val series: List<TagCount>,
        /** Top namespace-less tags (the parser's "misc" bucket). */
        val misc: List<TagCount>,
    ) {
        val isEmpty: Boolean get() = artists.isEmpty() && series.isEmpty() && misc.isEmpty()
    }

    private val EXCLUDED_NAMESPACES = setOf("date_added", "timestamp", "source", "language")
    private const val ARTIST_LIMIT = 5
    private const val SERIES_LIMIT = 5
    private const val MISC_LIMIT = 10

    fun compute(rows: List<HistoryStatsRow>): TagPreference {
        val counts = HashMap<Pair<String, String>, Int>()
        for (row in rows) {
            val tags = row.archive?.tags ?: continue
            // Distinct (namespace, value) pairs per archive: one archive
            // contributes at most 1 to any tag. parody canonicalizes to series
            // up front so the merged group counts a same-named tag once even
            // when an archive carries it under both namespaces.
            val distinct = tags.asSequence()
                .filter { (ns, _) -> ns !in EXCLUDED_NAMESPACES }
                .flatMap { (ns, values) ->
                    val canonical = if (ns == "parody") "series" else ns
                    values.asSequence().map { canonical to it }
                }
                .toSet()
            for (key in distinct) counts.merge(key, 1, Int::plus)
        }

        fun top(limit: Int, predicate: (String) -> Boolean): List<TagCount> =
            counts.asSequence()
                .filter { predicate(it.key.first) }
                .map { TagCount(it.key.second, it.value) }
                .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag })
                .take(limit)
                .toList()

        return TagPreference(
            artists = top(ARTIST_LIMIT) { it == "artist" },
            series = top(SERIES_LIMIT) { it == "series" },
            misc = top(MISC_LIMIT) { it == "misc" },
        )
    }
}
