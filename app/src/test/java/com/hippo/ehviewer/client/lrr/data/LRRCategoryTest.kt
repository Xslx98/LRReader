package com.lanraragi.reader.client.api.data

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import org.junit.Assert.*
import org.junit.Test

class LRRCategoryTest {

    @Test
    fun parseStaticCategory() {
        val json = """{"id": "cat1", "name": "Favorites", "archives": ["a1", "a2"], "pinned": "0", "search": ""}"""
        val cat = lrrJson.decodeFromString<LRRCategory>(json)
        assertEquals("cat1", cat.id)
        assertEquals("Favorites", cat.name)
        assertEquals(listOf("a1", "a2"), cat.archives)
        assertFalse(cat.isDynamic())
        assertFalse(cat.isPinned())
    }

    @Test
    fun parseDynamicCategory() {
        val json = """{"id": "cat2", "name": "Recent", "archives": [], "pinned": "1", "search": "date_added:>1700000"}"""
        val cat = lrrJson.decodeFromString<LRRCategory>(json)
        assertTrue(cat.isDynamic())
        assertTrue(cat.isPinned())
        assertEquals("date_added:>1700000", cat.search)
    }

    @Test
    fun parsePinnedAsInt() {
        // FlexibleStringSerializer handles integer → string
        val json = """{"id": "cat3", "name": "Test", "archives": [], "pinned": 1, "search": ""}"""
        val cat = lrrJson.decodeFromString<LRRCategory>(json)
        assertTrue(cat.isPinned())
    }

    @Test
    fun parsePinnedAsZeroInt() {
        val json = """{"id": "cat4", "name": "Test", "archives": [], "pinned": 0, "search": ""}"""
        val cat = lrrJson.decodeFromString<LRRCategory>(json)
        assertFalse(cat.isPinned())
    }

    @Test
    fun parseNullSearch() {
        val json = """{"id": "cat5", "name": "Test", "archives": []}"""
        val cat = lrrJson.decodeFromString<LRRCategory>(json)
        assertFalse(cat.isDynamic())
    }
}
