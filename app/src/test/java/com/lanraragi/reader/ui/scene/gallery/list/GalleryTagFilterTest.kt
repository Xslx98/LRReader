package com.lanraragi.reader.ui.scene.gallery.list

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The multi-tag filter lives in the screen's saved state, so a re-created screen keeps it. */
class GalleryTagFilterTest {

    @Test
    fun `tags are added once without their namespace, and a re-created screen keeps them`() {
        val saved = SavedStateHandle()
        val filter = GalleryTagFilter(saved)
        filter.toggle()

        assertEquals("alice", filter.add("artist:alice"))
        assertEquals("alice  english", filter.add("language:english"))
        assertEquals("alice  english", filter.add("artist:alice"))

        val restored = GalleryTagFilter(saved)
        assertTrue(restored.isOpen)
        assertEquals(listOf("alice", "english"), restored.tags)
        assertEquals("alice  english", restored.searchText())
    }

    @Test
    fun `back drops the last tag but never the first one`() {
        val filter = GalleryTagFilter()
        assertFalse("a closed filter has nothing to drop", filter.dropLast())
        filter.toggle()
        filter.add("a")
        filter.add("b")

        assertTrue(filter.dropLast())
        assertEquals(listOf("a"), filter.tags)
        assertFalse(filter.dropLast())
        assertEquals(listOf("a"), filter.tags)
    }

    @Test
    fun `closing the filter forgets its tags`() {
        val saved = SavedStateHandle()
        val filter = GalleryTagFilter(saved)
        filter.toggle()
        filter.add("a")

        filter.toggle()

        assertFalse(GalleryTagFilter(saved).isOpen)
        assertEquals(emptyList<String>(), GalleryTagFilter(saved).tags)
    }
}
