package com.lanraragi.reader.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginTagMergeTest {

    @Test
    fun parseNewTags_trimsTheServersLeadingSpaces() {
        assertEquals(listOf("artist:foo", "bare tag"), PluginTagMerge.parseNewTags(" artist:foo, bare tag"))
    }

    @Test
    fun parseNewTags_keepsTheFirstSpellingOfACaseOnlyDuplicate() {
        assertEquals(listOf("Artist:Foo", "b"), PluginTagMerge.parseNewTags(" Artist:Foo, artist:foo, b"))
    }

    @Test
    fun parseNewTags_emptyAnswers() {
        assertEquals(emptyList<String>(), PluginTagMerge.parseNewTags(null))
        assertEquals(emptyList<String>(), PluginTagMerge.parseNewTags(""))
        assertEquals(emptyList<String>(), PluginTagMerge.parseNewTags(" , "))
    }

    @Test
    fun additions_skipWhatTheServerHasInAnyCase() {
        assertEquals(
            listOf("series:new"),
            PluginTagMerge.additions("Artist:Foo, misc", listOf("artist:foo", "MISC", "series:new")),
        )
    }

    @Test
    fun additions_dropCaseOnlyDuplicatesAmongThemselves() {
        assertEquals(listOf("a:B"), PluginTagMerge.additions("", listOf("a:B", "a:b")))
    }

    @Test
    fun additions_neverAddASecondRating() {
        assertEquals(
            listOf("x"),
            PluginTagMerge.additions("rating:⭐⭐, y", listOf("rating:⭐⭐⭐⭐⭐", "x")),
        )
    }

    @Test
    fun additions_offerARatingOnlyWhenTheServerHasNone() {
        assertEquals(listOf("rating:⭐"), PluginTagMerge.additions("y", listOf("rating:⭐")))
    }

    @Test
    fun union_leavesTheServersTagsAsTheyAre() {
        assertEquals(
            "Artist:Foo, rating:⭐⭐, misc:x, new",
            PluginTagMerge.union(" Artist:Foo,rating:⭐⭐, misc:x", listOf("new")),
        )
    }

    @Test
    fun union_ontoNoTags() {
        assertEquals("new", PluginTagMerge.union("", listOf("new")))
    }
}
