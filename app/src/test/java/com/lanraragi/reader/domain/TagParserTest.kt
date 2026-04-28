package com.lanraragi.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagParserTest {

    // ── parseLrrTagString ───────────────────────────────────────────────

    @Test
    fun `parseLrrTagString returns empty map for empty input`() {
        assertTrue(parseLrrTagString("").isEmpty())
    }

    @Test
    fun `parseLrrTagString groups tags by namespace preserving order`() {
        val parsed = parseLrrTagString("artist:foo, parody:bar, artist:baz, raw_tag")

        assertEquals(listOf("artist", "parody", "misc"), parsed.keys.toList())
        assertEquals(listOf("foo", "baz"), parsed["artist"])
        assertEquals(listOf("bar"), parsed["parody"])
        assertEquals(listOf("raw_tag"), parsed["misc"])
    }

    @Test
    fun `parseLrrTagString trims whitespace around namespace and value`() {
        val parsed = parseLrrTagString("  artist : foo  ,  bare  ")

        assertEquals(listOf("foo"), parsed["artist"])
        assertEquals(listOf("bare"), parsed["misc"])
    }

    @Test
    fun `parseLrrTagString skips empty entries from consecutive commas`() {
        val parsed = parseLrrTagString("artist:foo,,parody:bar,")

        assertEquals(listOf("foo"), parsed["artist"])
        assertEquals(listOf("bar"), parsed["parody"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun `parseLrrTagString treats leading colon as a bare value`() {
        // ":value" has colonIdx == 0, which the parser treats as "no namespace"
        val parsed = parseLrrTagString(":value")
        assertEquals(listOf(":value"), parsed["misc"])
    }

    // ── groupFlatTags ───────────────────────────────────────────────────

    @Test
    fun `groupFlatTags returns empty map for null input`() {
        assertTrue(groupFlatTags(null).isEmpty())
    }

    @Test
    fun `groupFlatTags returns empty map for empty array`() {
        assertTrue(groupFlatTags(emptyArray()).isEmpty())
    }

    @Test
    fun `groupFlatTags groups items by namespace`() {
        val parsed = groupFlatTags(arrayOf("artist:foo", "artist:bar", "language:english", "tag"))

        assertEquals(listOf("artist", "language", "misc"), parsed.keys.toList())
        assertEquals(listOf("foo", "bar"), parsed["artist"])
        assertEquals(listOf("english"), parsed["language"])
        assertEquals(listOf("tag"), parsed["misc"])
    }

    // ── stripNamespace ──────────────────────────────────────────────────

    @Test
    fun `stripNamespace returns value portion when colon present`() {
        assertEquals("foo", stripNamespace("artist:foo"))
        assertEquals("english", stripNamespace("language:english"))
    }

    @Test
    fun `stripNamespace returns trimmed input when colon absent`() {
        assertEquals("bare", stripNamespace("bare"))
        assertEquals("bare", stripNamespace("  bare  "))
    }

    @Test
    fun `stripNamespace trims whitespace around value`() {
        assertEquals("foo", stripNamespace("artist:  foo  "))
    }
}
