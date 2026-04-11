package com.lanraragi.reader.client.api.data

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [LRRArchive] — data model, tag parsing, rating parsing.
 * Note: toGalleryInfo/toGalleryDetail are NOT tested here because they
 * depend on LRRAuthManager (SharedPreferences / Android context).
 */
class LRRArchiveTest {

    // ── Serialization ──────────────────────────────────────────────

    @Test
    fun parseMinimalArchive() {
        val json = """{"arcid":"abc123","title":"Test","tags":"","isnew":"false","extension":"zip","filename":"test.zip","pagecount":0,"progress":0,"lastreadtime":0}"""
        val archive = lrrJson.decodeFromString<LRRArchive>(json)
        assertEquals("abc123", archive.arcid)
        assertEquals("Test", archive.title)
        assertEquals("", archive.tags)
        assertEquals(0, archive.pagecount)
        assertNull(archive.summary)
    }

    @Test
    fun parseFullArchive() {
        val json = """{
            "arcid": "def456",
            "title": "Full Archive",
            "tags": "artist:foo, date_added:123",
            "isnew": "true",
            "extension": "cbz",
            "filename": "full.cbz",
            "pagecount": 42,
            "progress": 10,
            "lastreadtime": 1700000000,
            "summary": "A test summary"
        }"""
        val archive = lrrJson.decodeFromString<LRRArchive>(json)
        assertEquals("def456", archive.arcid)
        assertEquals("Full Archive", archive.title)
        assertEquals(42, archive.pagecount)
        assertEquals(10, archive.progress)
        assertEquals(1700000000L, archive.lastreadtime)
        assertEquals("A test summary", archive.summary)
        assertTrue(archive.isNew())
    }

    @Test
    fun parseIsNewAsBoolean() {
        val json = """{"arcid":"x","title":"T","tags":"","isnew":true,"extension":"","filename":"","pagecount":0,"progress":0,"lastreadtime":0}"""
        val archive = lrrJson.decodeFromString<LRRArchive>(json)
        assertEquals("true", archive.isnew)
        assertTrue(archive.isNew())
    }

    @Test
    fun parseIsNewAsString() {
        val json = """{"arcid":"x","title":"T","tags":"","isnew":"false","extension":"","filename":"","pagecount":0,"progress":0,"lastreadtime":0}"""
        val archive = lrrJson.decodeFromString<LRRArchive>(json)
        assertEquals("false", archive.isnew)
        assertFalse(archive.isNew())
    }

    // ── Tag parsing ────────────────────────────────────────────────

    @Test
    fun getParsedTags_withNamespaces() {
        val archive = makeArchive(tags = "artist:Akira, date_added:1700000, group:Circle X")
        val parsed = archive.getParsedTags()
        assertEquals(listOf("Akira"), parsed["artist"])
        assertEquals(listOf("1700000"), parsed["date_added"])
        assertEquals(listOf("Circle X"), parsed["group"])
    }

    @Test
    fun getParsedTags_noNamespace() {
        val archive = makeArchive(tags = "tag1, tag2, tag3")
        val parsed = archive.getParsedTags()
        assertEquals(listOf("tag1", "tag2", "tag3"), parsed["misc"])
    }

    @Test
    fun getParsedTags_mixed() {
        val archive = makeArchive(tags = "artist:foo, standalone, genre:action")
        val parsed = archive.getParsedTags()
        assertEquals(listOf("foo"), parsed["artist"])
        assertEquals(listOf("standalone"), parsed["misc"])
        assertEquals(listOf("action"), parsed["genre"])
    }

    @Test
    fun getParsedTags_empty() {
        val archive = makeArchive(tags = "")
        assertTrue(archive.getParsedTags().isEmpty())
    }

    @Test
    fun getParsedTags_multipleInSameNamespace() {
        val archive = makeArchive(tags = "artist:A, artist:B, artist:C")
        val parsed = archive.getParsedTags()
        assertEquals(listOf("A", "B", "C"), parsed["artist"])
    }

    @Test
    fun getSimpleTags_stripsNamespace() {
        val archive = makeArchive(tags = "artist:foo, bar, genre:action")
        val simple = archive.getSimpleTags()!!
        assertArrayEquals(arrayOf("foo", "bar", "action"), simple)
    }

    @Test
    fun getSimpleTags_empty() {
        val archive = makeArchive(tags = "")
        assertNull(archive.getSimpleTags())
    }

    // ── Rating ─────────────────────────────────────────────────────

    @Test
    fun parseRatingFromTags_emoji() {
        assertEquals(3.0f, LRRArchive.parseRatingFromTags("artist:foo, rating:⭐⭐⭐"), 0.01f)
    }

    @Test
    fun parseRatingFromTags_numeric() {
        assertEquals(4.5f, LRRArchive.parseRatingFromTags("rating:4.5"), 0.01f)
    }

    @Test
    fun parseRatingFromTags_none() {
        assertEquals(-1.0f, LRRArchive.parseRatingFromTags("artist:foo, genre:bar"), 0.01f)
    }

    @Test
    fun parseRatingFromTags_null() {
        assertEquals(-1.0f, LRRArchive.parseRatingFromTags(null), 0.01f)
    }

    @Test
    fun parseRatingFromTags_empty() {
        assertEquals(-1.0f, LRRArchive.parseRatingFromTags(""), 0.01f)
    }

    @Test
    fun parseRatingFromTags_fiveStarsMax() {
        assertEquals(5.0f, LRRArchive.parseRatingFromTags("rating:⭐⭐⭐⭐⭐⭐⭐"), 0.01f)
    }

    @Test
    fun parseRatingFromTags_oneStar() {
        assertEquals(1.0f, LRRArchive.parseRatingFromTags("rating:⭐"), 0.01f)
    }

    @Test
    fun parseRatingFromTags_invalidText() {
        assertEquals(-1.0f, LRRArchive.parseRatingFromTags("rating:good"), 0.01f)
    }

    @Test
    fun buildRatingEmoji() {
        assertEquals("⭐⭐⭐", LRRArchive.buildRatingEmoji(3))
        assertEquals("⭐⭐⭐⭐⭐", LRRArchive.buildRatingEmoji(7)) // capped at 5
        assertEquals("", LRRArchive.buildRatingEmoji(0))
    }

    // ── Thumbnail URL ──────────────────────────────────────────────

    @Test
    fun getThumbnailUrl() {
        val archive = makeArchive(arcid = "abc123")
        assertEquals(
            "https://server.test/api/archives/abc123/thumbnail",
            archive.getThumbnailUrl("https://server.test")
        )
    }

    // ── isNew ───────────────────────────────────────────────────────

    @Test
    fun isNew_caseInsensitive() {
        val archive = makeArchive()
        archive.isnew = "True"
        assertTrue(archive.isNew())
        archive.isnew = "TRUE"
        assertTrue(archive.isNew())
    }

    // ── Helper ──────────────────────────────────────────────────────

    private fun makeArchive(
        arcid: String = "test",
        title: String = "Test",
        tags: String = ""
    ): LRRArchive {
        return LRRArchive().apply {
            this.arcid = arcid
            this.title = title
            this.tags = tags
        }
    }
}
