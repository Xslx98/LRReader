package com.lanraragi.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RatingServiceTest {

    // ── parseRatingFromTags ─────────────────────────────────────────────

    @Test
    fun `parseRatingFromTags reads star emoji count when rating tag uses stars`() {
        assertEquals(3.0f, parseRatingFromTags("artist:foo, rating:⭐⭐⭐"), 0.01f)
    }

    @Test
    fun `parseRatingFromTags reads numeric value when rating tag uses a decimal`() {
        assertEquals(4.5f, parseRatingFromTags("rating:4.5"), 0.01f)
    }

    @Test
    fun `parseRatingFromTags returns -1 when no rating tag is present`() {
        assertEquals(-1.0f, parseRatingFromTags("artist:foo, genre:bar"), 0.01f)
    }

    @Test
    fun `parseRatingFromTags returns -1 for null input`() {
        assertEquals(-1.0f, parseRatingFromTags(null), 0.01f)
    }

    @Test
    fun `parseRatingFromTags returns -1 for empty input`() {
        assertEquals(-1.0f, parseRatingFromTags(""), 0.01f)
    }

    @Test
    fun `parseRatingFromTags caps star count at five`() {
        assertEquals(5.0f, parseRatingFromTags("rating:⭐⭐⭐⭐⭐⭐⭐"), 0.01f)
    }

    @Test
    fun `parseRatingFromTags reads single-star rating as 1`() {
        assertEquals(1.0f, parseRatingFromTags("rating:⭐"), 0.01f)
    }

    @Test
    fun `parseRatingFromTags returns -1 when value is non-numeric and has no stars`() {
        assertEquals(-1.0f, parseRatingFromTags("rating:good"), 0.01f)
    }

    // ── buildRatingEmoji ────────────────────────────────────────────────

    @Test
    fun `buildRatingEmoji renders the requested star count`() {
        assertEquals("⭐⭐⭐", buildRatingEmoji(3))
    }

    @Test
    fun `buildRatingEmoji caps at five stars`() {
        assertEquals("⭐⭐⭐⭐⭐", buildRatingEmoji(7))
    }

    @Test
    fun `buildRatingEmoji returns empty for zero stars`() {
        assertEquals("", buildRatingEmoji(0))
    }

    @Test
    fun `buildRatingEmoji clamps negative input to empty`() {
        assertEquals("", buildRatingEmoji(-3))
    }
}
