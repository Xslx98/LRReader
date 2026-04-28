package com.lanraragi.reader.domain

/**
 * Rating tag helpers for LANraragi.
 *
 * LRR servers store gallery ratings as a tag named `rating:` whose value
 * is either a string of one to five `⭐` codepoints or a decimal number.
 * This file is the single owner of that wire format so the UI layer
 * does not have to import the API DTO just to render or update it.
 */

private const val STAR_CODEPOINT = 0x2B50
private const val RATING_TAG_PREFIX = "rating:"
private const val MAX_STARS = 5
private const val NO_RATING = -1.0f

/**
 * Parse the LRR rating from a comma-separated tag string. Returns the
 * rating as a 1–5 float, or [NO_RATING] (-1) when no rating tag is
 * present, the value is non-numeric, or the input is null/empty.
 */
fun parseRatingFromTags(tags: String?): Float {
    if (tags.isNullOrEmpty()) return NO_RATING
    for (part in tags.split(",")) {
        val trimmed = part.trim()
        if (!trimmed.startsWith(RATING_TAG_PREFIX)) continue
        val value = trimmed.substring(RATING_TAG_PREFIX.length).trim()
        val starCount = countStars(value)
        if (starCount > 0) return starCount.coerceAtMost(MAX_STARS).toFloat()
        return value.toFloatOrNull() ?: NO_RATING
    }
    return NO_RATING
}

/**
 * Render an integer star count into the LRR rating tag value. Caps at
 * [MAX_STARS] so a `7` renders as `"⭐⭐⭐⭐⭐"`, not seven stars.
 */
fun buildRatingEmoji(starCount: Int): String =
    "⭐".repeat(starCount.coerceIn(0, MAX_STARS))

private fun countStars(value: String): Int {
    var count = 0
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        if (cp == STAR_CODEPOINT) count++
        i += Character.charCount(cp)
    }
    return count
}
