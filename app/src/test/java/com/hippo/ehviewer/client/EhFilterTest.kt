package com.hippo.ehviewer.client

import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.Filter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [EhFilter] filtering logic.
 *
 * Constructor no longer touches the database, so we can create an instance
 * via reflection and populate filter lists through [EhFilter.loadFromList].
 */
class EhFilterTest {

    private lateinit var ehFilter: EhFilter

    @Before
    fun setUp() {
        // Reset singleton
        val sInstanceField = EhFilter::class.java.getDeclaredField("sInstance")
        sInstanceField.isAccessible = true
        sInstanceField.set(null, null)

        // Create instance via reflection (private constructor, no DB call)
        val constructor = EhFilter::class.java.getDeclaredConstructor()
        constructor.isAccessible = true
        ehFilter = constructor.newInstance()
    }

    // ---- Helpers ----

    private fun addTitleFilter(text: String, enabled: Boolean = true) {
        ehFilter.loadFromList(listOf(
            Filter(mode = EhFilter.MODE_TITLE, text = text, enable = enabled)
        ))
    }

    private fun addUploaderFilter(text: String, enabled: Boolean = true) {
        ehFilter.loadFromList(listOf(
            Filter(mode = EhFilter.MODE_UPLOADER, text = text, enable = enabled)
        ))
    }

    private fun addTagFilter(text: String, enabled: Boolean = true) {
        ehFilter.loadFromList(listOf(
            Filter(mode = EhFilter.MODE_TAG, text = text, enable = enabled)
        ))
    }

    private fun addTagNamespaceFilter(text: String, enabled: Boolean = true) {
        ehFilter.loadFromList(listOf(
            Filter(mode = EhFilter.MODE_TAG_NAMESPACE, text = text, enable = enabled)
        ))
    }

    private fun makeGalleryInfo(
        title: String? = null,
        uploader: String? = null,
        tags: Array<String>? = null
    ): GalleryInfo = GalleryInfo().apply {
        this.title = title
        this.uploader = uploader
        this.simpleTags = tags
    }

    // ---- filterTitle tests ----

    @Test
    fun filterTitle_nullInfo_returnsFalse() {
        assertFalse(ehFilter.filterTitle(null))
    }

    @Test
    fun filterTitle_noFilters_returnsTrue() {
        val info = makeGalleryInfo(title = "Anything")
        assertTrue(ehFilter.filterTitle(info))
    }

    @Test
    fun filterTitle_matchingFilter_returnsFalse() {
        addTitleFilter("banned")
        val info = makeGalleryInfo(title = "This is Banned Content")
        // filterTitle returns false when filter matches (item should be hidden)
        assertFalse(ehFilter.filterTitle(info))
    }

    @Test
    fun filterTitle_noMatch_returnsTrue() {
        addTitleFilter("banned")
        val info = makeGalleryInfo(title = "Perfectly Fine Title")
        assertTrue(ehFilter.filterTitle(info))
    }

    @Test
    fun filterTitle_caseInsensitive_returnsFalse() {
        addTitleFilter("test")
        val info = makeGalleryInfo(title = "A TEST Title")
        assertFalse(ehFilter.filterTitle(info))
    }

    @Test
    fun filterTitle_partialMatch_returnsFalse() {
        addTitleFilter("partial")
        val info = makeGalleryInfo(title = "This has a partial match in it")
        assertFalse(ehFilter.filterTitle(info))
    }

    @Test
    fun filterTitle_nullTitle_returnsTrue() {
        addTitleFilter("test")
        val info = makeGalleryInfo(title = null)
        assertTrue(ehFilter.filterTitle(info))
    }

    @Test
    fun filterTitle_disabledFilter_doesNotFilter() {
        addTitleFilter("banned", enabled = false)
        val info = makeGalleryInfo(title = "This is Banned Content")
        assertTrue(ehFilter.filterTitle(info))
    }

    @Test
    fun filterTitle_multipleFilters_anyMatch_returnsFalse() {
        addTitleFilter("alpha")
        addTitleFilter("beta")
        val info = makeGalleryInfo(title = "Contains beta somewhere")
        assertFalse(ehFilter.filterTitle(info))
    }

    // ---- filterUploader tests ----

    @Test
    fun filterUploader_nullInfo_returnsFalse() {
        assertFalse(ehFilter.filterUploader(null))
    }

    @Test
    fun filterUploader_noFilters_returnsTrue() {
        val info = makeGalleryInfo(uploader = "anyone")
        assertTrue(ehFilter.filterUploader(info))
    }

    @Test
    fun filterUploader_matchingUploader_returnsFalse() {
        addUploaderFilter("baduser")
        val info = makeGalleryInfo(uploader = "baduser")
        assertFalse(ehFilter.filterUploader(info))
    }

    @Test
    fun filterUploader_noMatch_returnsTrue() {
        addUploaderFilter("baduser")
        val info = makeGalleryInfo(uploader = "gooduser")
        assertTrue(ehFilter.filterUploader(info))
    }

    @Test
    fun filterUploader_exactMatchOnly_returnsTrue() {
        addUploaderFilter("bad")
        // "baduser" is NOT an exact match for "bad"
        val info = makeGalleryInfo(uploader = "baduser")
        assertTrue(ehFilter.filterUploader(info))
    }

    @Test
    fun filterUploader_nullUploader_returnsTrue() {
        addUploaderFilter("baduser")
        val info = makeGalleryInfo(uploader = null)
        assertTrue(ehFilter.filterUploader(info))
    }

    @Test
    fun filterUploader_disabledFilter_doesNotFilter() {
        addUploaderFilter("baduser", enabled = false)
        val info = makeGalleryInfo(uploader = "baduser")
        assertTrue(ehFilter.filterUploader(info))
    }

    // ---- filterTag tests ----

    @Test
    fun filterTag_nullInfo_returnsFalse() {
        assertFalse(ehFilter.filterTag(null))
    }

    @Test
    fun filterTag_noFilters_returnsTrue() {
        val info = makeGalleryInfo(tags = arrayOf("artist:someone"))
        assertTrue(ehFilter.filterTag(info))
    }

    @Test
    fun filterTag_matchingNamespaceTag_returnsFalse() {
        addTagFilter("artist:badartist")
        val info = makeGalleryInfo(tags = arrayOf("artist:badartist", "parody:series"))
        assertFalse(ehFilter.filterTag(info))
    }

    @Test
    fun filterTag_matchingTagNameOnly_returnsFalse() {
        // Filter without namespace matches any namespace
        addTagFilter("badtag")
        val info = makeGalleryInfo(tags = arrayOf("misc:badtag"))
        assertFalse(ehFilter.filterTag(info))
    }

    @Test
    fun filterTag_noMatch_returnsTrue() {
        addTagFilter("artist:badartist")
        val info = makeGalleryInfo(tags = arrayOf("artist:goodartist", "parody:series"))
        assertTrue(ehFilter.filterTag(info))
    }

    @Test
    fun filterTag_nullTags_returnsTrue() {
        addTagFilter("test")
        val info = makeGalleryInfo(tags = null)
        assertTrue(ehFilter.filterTag(info))
    }

    @Test
    fun filterTag_emptyTags_returnsTrue() {
        addTagFilter("test")
        val info = makeGalleryInfo(tags = emptyArray())
        assertTrue(ehFilter.filterTag(info))
    }

    @Test
    fun filterTag_disabledFilter_doesNotFilter() {
        addTagFilter("artist:badartist", enabled = false)
        val info = makeGalleryInfo(tags = arrayOf("artist:badartist"))
        assertTrue(ehFilter.filterTag(info))
    }

    @Test
    fun filterTag_differentNamespace_returnsTrue() {
        addTagFilter("artist:someone")
        // Tag has namespace "parody", filter has "artist" -- should NOT match
        val info = makeGalleryInfo(tags = arrayOf("parody:someone"))
        assertTrue(ehFilter.filterTag(info))
    }

    // ---- filterTagNamespace tests ----

    @Test
    fun filterTagNamespace_nullInfo_returnsFalse() {
        assertFalse(ehFilter.filterTagNamespace(null))
    }

    @Test
    fun filterTagNamespace_noFilters_returnsTrue() {
        val info = makeGalleryInfo(tags = arrayOf("artist:someone"))
        assertTrue(ehFilter.filterTagNamespace(info))
    }

    @Test
    fun filterTagNamespace_matchingNamespace_returnsFalse() {
        addTagNamespaceFilter("artist")
        val info = makeGalleryInfo(tags = arrayOf("artist:someone"))
        assertFalse(ehFilter.filterTagNamespace(info))
    }

    @Test
    fun filterTagNamespace_noMatch_returnsTrue() {
        addTagNamespaceFilter("artist")
        val info = makeGalleryInfo(tags = arrayOf("parody:series"))
        assertTrue(ehFilter.filterTagNamespace(info))
    }

    @Test
    fun filterTagNamespace_tagWithoutNamespace_returnsTrue() {
        addTagNamespaceFilter("artist")
        val info = makeGalleryInfo(tags = arrayOf("tagwithoutns"))
        assertTrue(ehFilter.filterTagNamespace(info))
    }

    @Test
    fun filterTagNamespace_disabledFilter_doesNotFilter() {
        addTagNamespaceFilter("artist", enabled = false)
        val info = makeGalleryInfo(tags = arrayOf("artist:someone"))
        assertTrue(ehFilter.filterTagNamespace(info))
    }

    // ---- needTags ----

    @Test
    fun needTags_emptyFilters_returnsFalse() {
        assertFalse(ehFilter.needTags())
    }

    @Test
    fun needTags_withTagFilter_returnsTrue() {
        addTagFilter("sometag")
        assertTrue(ehFilter.needTags())
    }

    @Test
    fun needTags_withTagNamespaceFilter_returnsTrue() {
        addTagNamespaceFilter("artist")
        assertTrue(ehFilter.needTags())
    }

    @Test
    fun needTags_withOnlyTitleFilter_returnsFalse() {
        addTitleFilter("sometitle")
        assertFalse(ehFilter.needTags())
    }
}
