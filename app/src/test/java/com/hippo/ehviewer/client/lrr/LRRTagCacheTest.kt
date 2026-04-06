package com.hippo.ehviewer.client.lrr

import com.hippo.ehviewer.client.lrr.data.LRRTagStat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LRRTagCacheTest {

    @Before
    fun setUp() {
        LRRTagCache.clear()
    }

    @Test
    fun suggestEmptyKeywordReturnsEmpty() {
        val result = LRRTagCache.suggest("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun suggestBlankKeywordReturnsEmpty() {
        val result = LRRTagCache.suggest("   ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun suggestWithEmptyCacheReturnsEmpty() {
        val result = LRRTagCache.suggest("artist")
        assertTrue(result.isEmpty())
    }

    @Test
    fun clearResetsCacheState() {
        LRRTagCache.clear()
        assertFalse(LRRTagCache.isPopulated())
        assertTrue(LRRTagCache.needsRefresh())
    }

    @Test
    fun suggestMatchesTextSubstring() {
        // Populate cache via reflection for unit testing
        populateCache(
            listOf(
                LRRTagStat("artist", "test_artist", 10),
                LRRTagStat("parody", "my_series", 5)
            )
        )

        val result = LRRTagCache.suggest("art")
        assertEquals(1, result.size)
        assertEquals("test_artist", result[0].text)
    }

    @Test
    fun suggestMatchesNamespaceColonText() {
        populateCache(
            listOf(
                LRRTagStat("artist", "john", 10),
                LRRTagStat("parody", "john_series", 5)
            )
        )

        val result = LRRTagCache.suggest("artist:john")
        assertEquals(1, result.size)
        assertEquals("john", result[0].text)
        assertEquals("artist", result[0].namespace)
    }

    @Test
    fun suggestIsCaseInsensitive() {
        populateCache(
            listOf(LRRTagStat("artist", "TestArtist", 10))
        )

        val result = LRRTagCache.suggest("testartist")
        assertEquals(1, result.size)
    }

    @Test
    fun suggestExcludesDateAddedNamespace() {
        populateCache(
            listOf(
                LRRTagStat("date_added", "1700000", 100),
                LRRTagStat("artist", "some_date_added_fan", 5)
            )
        )

        val result = LRRTagCache.suggest("date")
        assertEquals(1, result.size)
        assertEquals("artist", result[0].namespace)
    }

    @Test
    fun suggestExcludesSourceNamespace() {
        populateCache(
            listOf(
                LRRTagStat("source", "http://example.com", 50),
                LRRTagStat("parody", "source_material", 5)
            )
        )

        val result = LRRTagCache.suggest("source")
        assertEquals(1, result.size)
        assertEquals("parody", result[0].namespace)
    }

    @Test
    fun suggestSortsByWeightDescending() {
        populateCache(
            listOf(
                LRRTagStat("artist", "low_weight", 1),
                LRRTagStat("artist", "high_weight", 100),
                LRRTagStat("artist", "mid_weight", 50)
            )
        )

        val result = LRRTagCache.suggest("weight")
        assertEquals(3, result.size)
        assertEquals("high_weight", result[0].text)
        assertEquals("mid_weight", result[1].text)
        assertEquals("low_weight", result[2].text)
    }

    @Test
    fun suggestRespectsLimit() {
        val tags = (1..30).map { LRRTagStat("artist", "tag_$it", it) }
        populateCache(tags)

        val result = LRRTagCache.suggest("tag", limit = 5)
        assertEquals(5, result.size)
    }

    @Test
    fun isPopulatedReturnsTrueWhenCacheHasTags() {
        populateCache(listOf(LRRTagStat("artist", "test", 1)))
        assertTrue(LRRTagCache.isPopulated())
    }

    /**
     * Populate the cache directly for testing without network calls.
     * Uses reflection since the tags field is private.
     */
    private fun populateCache(tags: List<LRRTagStat>) {
        val tagsField = LRRTagCache::class.java.getDeclaredField("tags")
        tagsField.isAccessible = true
        tagsField.set(LRRTagCache, tags)

        val keysField = LRRTagCache::class.java.getDeclaredField("searchKeys")

        keysField.isAccessible = true

        keysField.set(LRRTagCache, tags.map { "${it.namespace}:${it.text}".lowercase() })

        val timeField = LRRTagCache::class.java.getDeclaredField("lastFetchTime")
        timeField.isAccessible = true
        timeField.set(LRRTagCache, System.currentTimeMillis())
    }
}
