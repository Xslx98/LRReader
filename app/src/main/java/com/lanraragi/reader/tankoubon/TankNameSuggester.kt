package com.lanraragi.reader.tankoubon

import kotlin.math.ceil

/**
 * Name suggestion for a tankoubon created from a selection (spec
 * 2026-09-22 §9): what the member titles have in common once their
 * per-volume noise is gone.
 *
 * Pipeline per title: strip leading/trailing bracket blocks → strip
 * episode markers ([EpisodeMarkers], plus a trailing bare number) → clean
 * separator residue. Then the common token PREFIX + SUFFIX across titles;
 * if that is shorter than [MIN_LENGTH], the tokens present in at least
 * half of the titles; else null (leave the box empty). 番外 is a kind,
 * not an ordinal, so it is kept.
 */
object TankNameSuggester {

    private const val MIN_LENGTH = 2

    private val LEADING_BRACKETS = Regex("^(?:\\s*[\\[(【（][^\\])】）]*[\\])】）]\\s*)+")
    private val TRAILING_BRACKETS = Regex("(?:\\s*[\\[(【（][^\\])】）]*[\\])】）]\\s*)+$")
    private val EMPTY_BRACKETS = Regex("[\\[(【（]\\s*[\\])】）]")
    private const val SEPARATORS = "\\-–—~～・·:：_/"
    private val EDGE_SEPARATORS = Regex("^[\\s$SEPARATORS]+|[\\s$SEPARATORS]+$")
    private val TRAILING_NUMBER = Regex("[\\s$SEPARATORS]*[0-9]+\\s*$")
    private val TOKEN_SPLIT = Regex("[\\s$SEPARATORS]+")
    private val WHITESPACE = Regex("\\s+")
    private val NO_LETTERS = Regex("^[0-9\\p{Punct}\\s]*$")

    /** The suggestion, or null when the titles share nothing usable. */
    fun suggest(titles: List<String>): String? {
        val stripped = titles.map { strip(it) }.filter { it.isNotEmpty() }
        if (stripped.isEmpty()) return null
        if (stripped.size == 1) return stripped.single().takeIf { it.length >= MIN_LENGTH }

        val tokenLists = stripped.map { tokens(it) }
        val sandwich = clean((commonPrefix(tokenLists) + commonSuffix(tokenLists)).joinToString(" "))
        if (sandwich.length >= MIN_LENGTH) return sandwich

        val majority = clean(majorityTokens(tokenLists).joinToString(" "))
        return majority.takeIf { it.length >= MIN_LENGTH }
    }

    /** One title with its volume noise removed (steps 1–3). */
    fun strip(title: String): String {
        val normalized = EpisodeMarkers.normalizeWidth(title)
        val unbracketed = normalized
            .replace(LEADING_BRACKETS, "")
            .replace(TRAILING_BRACKETS, "")
            .ifBlank { normalized }
        val noMarkers = unbracketed
            .replace(EpisodeMarkers.CJK_EPISODE, " ")
            .replace(EpisodeMarkers.LATIN_EPISODE, " ")
            .replace(EpisodeMarkers.SUB_ORDER, " ")
        // A trailing bare number is the volume only when nothing else said so
        // ("Extra 01" yes; "Series 2020 Vol.1" keeps its 2020).
        val hadMarker = noMarkers != unbracketed
        return clean(if (hadMarker) noMarkers else noMarkers.replace(TRAILING_NUMBER, ""))
    }

    private fun clean(s: String): String = s
        .replace(EMPTY_BRACKETS, "")
        .replace(WHITESPACE, " ")
        .replace(EDGE_SEPARATORS, "")
        .trim()

    private fun tokens(s: String): List<String> = s.split(TOKEN_SPLIT).filter { it.isNotEmpty() }

    private fun commonPrefix(lists: List<List<String>>): List<String> {
        val shortest = lists.minOf { it.size }
        val out = ArrayList<String>()
        for (i in 0 until shortest) {
            val t = lists[0][i]
            if (lists.all { it[i].equals(t, ignoreCase = true) }) out.add(t) else break
        }
        return out
    }

    /** Suffix tokens beyond the prefix (never re-uses a prefix token). */
    private fun commonSuffix(lists: List<List<String>>): List<String> {
        val prefix = commonPrefix(lists).size
        val room = lists.minOf { it.size } - prefix
        val out = ArrayList<String>()
        for (k in 1..room) {
            val t = lists[0][lists[0].size - k]
            if (lists.all { it[it.size - k].equals(t, ignoreCase = true) }) out.add(0, t) else break
        }
        return out
    }

    /** Tokens present in at least half of the titles, first-seen order; short / numeric / punctuation-only tokens skipped. */
    private fun majorityTokens(lists: List<List<String>>): List<String> {
        val threshold = ceil(lists.size / 2.0).toInt()
        val seen = LinkedHashMap<String, String>() // lowercase → first spelling
        for (list in lists) for (t in list) seen.putIfAbsent(t.lowercase(), t)
        return seen.entries
            .filter { (key, _) -> key.length >= MIN_LENGTH && !NO_LETTERS.matches(key) }
            .filter { (key, _) -> lists.count { list -> list.any { it.lowercase() == key } } >= threshold }
            .map { it.value }
    }
}
