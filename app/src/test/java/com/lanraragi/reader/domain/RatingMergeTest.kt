package com.lanraragi.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [mergeRatingIntoTags] contract, shared by the archive detail page and
 * the tankoubon detail page: replace (or strip) the `rating:` slot, keep
 * every other tag in order, never leave dangling commas.
 */
class RatingMergeTest {

    @Test
    fun replacesExistingRatingInPlaceOrder() {
        assertEquals(
            "artist:foo, language:english, rating:⭐⭐⭐",
            mergeRatingIntoTags("artist:foo, rating:⭐, language:english", 3f),
        )
    }

    @Test
    fun appendsRatingWhenAbsent() {
        assertEquals("artist:foo, rating:⭐⭐⭐⭐", mergeRatingIntoTags("artist:foo", 4f))
    }

    @Test
    fun emptyOrNullTagsYieldJustTheRating() {
        assertEquals("rating:⭐⭐", mergeRatingIntoTags(null, 2f))
        assertEquals("rating:⭐⭐", mergeRatingIntoTags("", 2f))
    }

    @Test
    fun zeroStripsTheRatingTagWithoutDanglingCommas() {
        assertEquals("artist:foo, language:english", mergeRatingIntoTags("artist:foo, rating:⭐⭐, language:english", 0f))
        assertEquals("artist:foo", mergeRatingIntoTags("rating:⭐⭐, artist:foo", 0f))
        assertEquals("", mergeRatingIntoTags("rating:⭐⭐", 0f))
    }

    @Test
    fun ratingIsRoundedToWholeStarsAndCapped() {
        assertEquals("rating:⭐⭐⭐", mergeRatingIntoTags("", 2.6f))
        assertEquals("rating:⭐⭐⭐⭐⭐", mergeRatingIntoTags("", 7f))
    }

    @Test
    fun numericRatingIsReplacedToo() {
        assertEquals("artist:foo, rating:⭐", mergeRatingIntoTags("artist:foo, rating:4", 1f))
    }
}
