package com.hippo.ehviewer.ui.scene.gallery.detail

import com.lanraragi.reader.domain.TagGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TagEditDialog] tag serialization/parsing logic.
 *
 * Covers the public [TagEditDialog.tagsToString] method and the private
 * [parseTagGroups] / [editableGroupsToString] methods via reflection,
 * verifying round-trip fidelity for LANraragi tag format.
 */
class TagEditDialogTest {

    // ---- tagsToString ----

    @Test
    fun tagsToString_nullList_returnsEmpty() {
        assertEquals("", TagEditDialog.tagsToString(null))
    }

    @Test
    fun tagsToString_emptyList_returnsEmpty() {
        assertEquals("", TagEditDialog.tagsToString(emptyList()))
    }

    @Test
    fun tagsToString_singleGroup_singleTag_formatsCorrectly() {
        val result = TagEditDialog.tagsToString(listOf(TagGroup("artist", listOf("picasso"))))
        assertEquals("artist:picasso", result)
    }

    @Test
    fun tagsToString_singleGroup_multipleTags_formatsCorrectly() {
        val result = TagEditDialog.tagsToString(
            listOf(TagGroup("artist", listOf("picasso", "monet")))
        )
        assertEquals("artist:picasso, artist:monet", result)
    }

    @Test
    fun tagsToString_multipleGroups_joinsWithComma() {
        val result = TagEditDialog.tagsToString(
            listOf(
                TagGroup("artist", listOf("picasso")),
                TagGroup("parody", listOf("mona lisa")),
            )
        )
        assertEquals("artist:picasso, parody:mona lisa", result)
    }

    @Test
    fun tagsToString_emptyGroup_contributsNothing() {
        val result = TagEditDialog.tagsToString(listOf(TagGroup("artist", emptyList())))
        assertEquals("", result)
    }

    @Test
    fun tagsToString_mixedEmptyAndNonEmptyGroups() {
        val result = TagEditDialog.tagsToString(
            listOf(
                TagGroup("empty", emptyList()),
                TagGroup("series", listOf("test_series")),
            )
        )
        assertEquals("series:test_series", result)
    }

    // ---- parseTagGroups (private, tested via reflection) ----

    @Test
    fun parseTagGroups_nullList_returnsEmptyList() {
        assertTrue(invokeParseTagGroups(null).isEmpty())
    }

    @Test
    fun parseTagGroups_emptyList_returnsEmptyList() {
        assertTrue(invokeParseTagGroups(emptyList()).isEmpty())
    }

    @Test
    fun parseTagGroups_parsesNamespacesCorrectly() {
        val result = invokeParseTagGroups(
            listOf(TagGroup("artist", listOf("picasso", "monet")))
        )
        assertEquals(1, result.size)
        assertEquals("artist", result[0].first)
        assertEquals(listOf("picasso", "monet"), result[0].second)
    }

    @Test
    fun parseTagGroups_multipleGroups_preservesOrder() {
        val result = invokeParseTagGroups(
            listOf(
                TagGroup("artist", listOf("a1")),
                TagGroup("parody", listOf("p1")),
                TagGroup("language", listOf("english")),
            )
        )
        assertEquals(3, result.size)
        assertEquals("artist", result[0].first)
        assertEquals("parody", result[1].first)
        assertEquals("language", result[2].first)
    }

    // ---- editableGroupsToString (private, tested via reflection) ----

    @Test
    fun editableGroupsToString_emptyGroups_returnsEmpty() {
        val result = invokeEditableGroupsToString(emptyList())
        assertEquals("", result)
    }

    @Test
    fun editableGroupsToString_singleGroup_formatsCorrectly() {
        val result = invokeEditableGroupsToString(listOf("artist" to listOf("picasso")))
        assertEquals("artist:picasso", result)
    }

    @Test
    fun editableGroupsToString_multipleGroups_joinsWithComma() {
        val result = invokeEditableGroupsToString(
            listOf("artist" to listOf("picasso"), "parody" to listOf("mona lisa"))
        )
        assertEquals("artist:picasso, parody:mona lisa", result)
    }

    @Test
    fun editableGroupsToString_skipsBlankTags() {
        val result = invokeEditableGroupsToString(
            listOf("artist" to listOf("picasso", "", "  ", "monet"))
        )
        assertEquals("artist:picasso, artist:monet", result)
    }

    // ---- Round-trip: tagsToString -> parseTagGroups -> editableGroupsToString ----

    @Test
    fun roundTrip_parseAndReconstruct_matchesOriginal() {
        val groups = listOf(
            TagGroup("artist", listOf("da_vinci", "rembrandt")),
            TagGroup("parody", listOf("starry_night")),
        )

        val tagString = TagEditDialog.tagsToString(groups)
        val parsed = invokeParseTagGroups(groups)
        val reconstructed = invokeEditableGroupsToString(parsed)

        assertEquals(tagString, reconstructed)
    }

    // ---- Reflection helpers ----

    /**
     * Invoke the private [TagEditDialog.parseTagGroups] method via reflection.
     * Returns a list of pairs (namespace, tags) for easy assertion.
     */
    private fun invokeParseTagGroups(
        tagGroups: List<TagGroup>?
    ): List<Pair<String, List<String>>> {
        val method = TagEditDialog::class.java.getDeclaredMethod(
            "parseTagGroups", List::class.java
        )
        method.isAccessible = true
        val result = method.invoke(TagEditDialog, tagGroups) as List<*>
        return result.map { group ->
            val cls = group!!::class.java
            val nsField = cls.getDeclaredField("namespace")
            nsField.isAccessible = true
            val tagsField = cls.getDeclaredField("tags")
            tagsField.isAccessible = true
            val namespace = nsField.get(group) as String
            @Suppress("UNCHECKED_CAST")
            val tags = tagsField.get(group) as List<String>
            namespace to tags.toList()
        }
    }

    /**
     * Invoke the private [TagEditDialog.editableGroupsToString] method via reflection.
     * Takes a list of pairs (namespace, tags) and converts to EditableTagGroup instances.
     */
    private fun invokeEditableGroupsToString(
        groups: List<Pair<String, List<String>>>
    ): String {
        val editableClass = Class.forName(
            "com.hippo.ehviewer.ui.scene.gallery.detail.TagEditDialog\$EditableTagGroup"
        )
        val constructor = editableClass.declaredConstructors.first()
        constructor.isAccessible = true

        val editableGroups = groups.map { (namespace, tags) ->
            constructor.newInstance(namespace, tags.toMutableList())
        }

        val method = TagEditDialog::class.java.getDeclaredMethod(
            "editableGroupsToString", List::class.java
        )
        method.isAccessible = true
        return method.invoke(TagEditDialog, editableGroups) as String
    }
}
