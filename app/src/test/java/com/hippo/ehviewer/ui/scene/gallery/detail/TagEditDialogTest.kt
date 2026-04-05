package com.hippo.ehviewer.ui.scene.gallery.detail

import com.hippo.ehviewer.client.data.GalleryTagGroup
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
    fun tagsToString_nullArray_returnsEmpty() {
        assertEquals("", TagEditDialog.tagsToString(null))
    }

    @Test
    fun tagsToString_emptyArray_returnsEmpty() {
        assertEquals("", TagEditDialog.tagsToString(emptyArray()))
    }

    @Test
    fun tagsToString_singleGroup_singleTag_formatsCorrectly() {
        val group = GalleryTagGroup().apply {
            groupName = "artist"
            addTag("picasso")
        }
        val result = TagEditDialog.tagsToString(arrayOf(group))
        assertEquals("artist:picasso", result)
    }

    @Test
    fun tagsToString_singleGroup_multipleTags_formatsCorrectly() {
        val group = GalleryTagGroup().apply {
            groupName = "artist"
            addTag("picasso")
            addTag("monet")
        }
        val result = TagEditDialog.tagsToString(arrayOf(group))
        assertEquals("artist:picasso, artist:monet", result)
    }

    @Test
    fun tagsToString_multipleGroups_joinsWithComma() {
        val group1 = GalleryTagGroup().apply {
            groupName = "artist"
            addTag("picasso")
        }
        val group2 = GalleryTagGroup().apply {
            groupName = "parody"
            addTag("mona lisa")
        }
        val result = TagEditDialog.tagsToString(arrayOf(group1, group2))
        assertEquals("artist:picasso, parody:mona lisa", result)
    }

    @Test
    fun tagsToString_nullGroupName_usesMisc() {
        val group = GalleryTagGroup().apply {
            groupName = null
            addTag("sometag")
        }
        val result = TagEditDialog.tagsToString(arrayOf(group))
        assertEquals("misc:sometag", result)
    }

    @Test
    fun tagsToString_emptyGroup_contributsNothing() {
        val group = GalleryTagGroup().apply {
            groupName = "artist"
            // no tags added
        }
        val result = TagEditDialog.tagsToString(arrayOf(group))
        assertEquals("", result)
    }

    @Test
    fun tagsToString_mixedEmptyAndNonEmptyGroups() {
        val empty = GalleryTagGroup().apply {
            groupName = "empty"
        }
        val nonEmpty = GalleryTagGroup().apply {
            groupName = "series"
            addTag("test_series")
        }
        val result = TagEditDialog.tagsToString(arrayOf(empty, nonEmpty))
        assertEquals("series:test_series", result)
    }

    // ---- parseTagGroups (private, tested via reflection) ----

    @Test
    fun parseTagGroups_nullArray_returnsEmptyList() {
        val result = invokeParseTagGroups(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseTagGroups_emptyArray_returnsEmptyList() {
        val result = invokeParseTagGroups(emptyArray())
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseTagGroups_parsesNamespacesCorrectly() {
        val group = GalleryTagGroup().apply {
            groupName = "artist"
            addTag("picasso")
            addTag("monet")
        }
        val result = invokeParseTagGroups(arrayOf(group))
        assertEquals(1, result.size)
        assertEquals("artist", result[0].first)
        assertEquals(listOf("picasso", "monet"), result[0].second)
    }

    @Test
    fun parseTagGroups_nullGroupName_mapToMisc() {
        val group = GalleryTagGroup().apply {
            groupName = null
            addTag("orphan_tag")
        }
        val result = invokeParseTagGroups(arrayOf(group))
        assertEquals(1, result.size)
        assertEquals("misc", result[0].first)
        assertEquals(listOf("orphan_tag"), result[0].second)
    }

    @Test
    fun parseTagGroups_multipleGroups_preservesOrder() {
        val g1 = GalleryTagGroup().apply {
            groupName = "artist"
            addTag("a1")
        }
        val g2 = GalleryTagGroup().apply {
            groupName = "parody"
            addTag("p1")
        }
        val g3 = GalleryTagGroup().apply {
            groupName = "language"
            addTag("english")
        }
        val result = invokeParseTagGroups(arrayOf(g1, g2, g3))
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
        val groups = listOf("artist" to listOf("picasso"))
        val result = invokeEditableGroupsToString(groups)
        assertEquals("artist:picasso", result)
    }

    @Test
    fun editableGroupsToString_multipleGroups_joinsWithComma() {
        val groups = listOf(
            "artist" to listOf("picasso"),
            "parody" to listOf("mona lisa")
        )
        val result = invokeEditableGroupsToString(groups)
        assertEquals("artist:picasso, parody:mona lisa", result)
    }

    @Test
    fun editableGroupsToString_skipsBlankTags() {
        val groups = listOf("artist" to listOf("picasso", "", "  ", "monet"))
        val result = invokeEditableGroupsToString(groups)
        assertEquals("artist:picasso, artist:monet", result)
    }

    // ---- Round-trip: tagsToString -> parseTagGroups -> editableGroupsToString ----

    @Test
    fun roundTrip_parseAndReconstruct_matchesOriginal() {
        val g1 = GalleryTagGroup().apply {
            groupName = "artist"
            addTag("da_vinci")
            addTag("rembrandt")
        }
        val g2 = GalleryTagGroup().apply {
            groupName = "parody"
            addTag("starry_night")
        }
        val groups = arrayOf(g1, g2)

        // Forward: tags -> string
        val tagString = TagEditDialog.tagsToString(groups)

        // Parse into editable model
        val parsed = invokeParseTagGroups(groups)

        // Reconstruct from editable model
        val reconstructed = invokeEditableGroupsToString(parsed)

        assertEquals(tagString, reconstructed)
    }

    // ---- Reflection helpers ----

    /**
     * Invoke the private [TagEditDialog.parseTagGroups] method via reflection.
     * Returns a list of pairs (namespace, tags) for easy assertion.
     */
    private fun invokeParseTagGroups(
        tagGroups: Array<GalleryTagGroup>?
    ): List<Pair<String, List<String>>> {
        val method = TagEditDialog::class.java.getDeclaredMethod(
            "parseTagGroups", Array<GalleryTagGroup>::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(TagEditDialog, tagGroups) as List<*>
        // Each element is an EditableTagGroup (private inner data class)
        // Access fields via reflection
        return result.map { group ->
            val cls = group!!::class.java
            val nsField = cls.getDeclaredField("namespace")
            nsField.isAccessible = true
            val tagsField = cls.getDeclaredField("tags")
            tagsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
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
        // First, create EditableTagGroup instances via reflection
        val editableClass = Class.forName(
            "com.hippo.ehviewer.ui.scene.gallery.detail.TagEditDialog\$EditableTagGroup"
        )
        val constructor = editableClass.declaredConstructors.first()
        constructor.isAccessible = true

        val editableGroups = groups.map { (namespace, tags) ->
            constructor.newInstance(namespace, tags.toMutableList())
        }

        // Then invoke editableGroupsToString
        val method = TagEditDialog::class.java.getDeclaredMethod(
            "editableGroupsToString", List::class.java
        )
        method.isAccessible = true
        return method.invoke(TagEditDialog, editableGroups) as String
    }
}
