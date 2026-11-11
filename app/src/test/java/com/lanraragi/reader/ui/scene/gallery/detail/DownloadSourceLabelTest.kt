package com.lanraragi.reader.ui.scene.gallery.detail

import com.lanraragi.reader.ui.scene.gallery.detail.DetailActionHandler.Companion.downloadSourceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #104: one download row per arcid is shared by mirrors on other profiles,
 * so the detail page's download button names the profile the row was
 * downloaded through whenever that is not the page's own source.
 */
class DownloadSourceLabelTest {

    private val names = mapOf(1L to "Server B", 2L to "Server C")

    private fun label(row: Long, page: Long, active: Long? = 2L): String? =
        downloadSourceLabel(row, page, active, DELETED) { names[it] }

    @Test
    fun sameSourceAsThePage_hasNoLabel() {
        assertNull(label(row = 2L, page = 2L))
    }

    @Test
    fun anotherSource_isNamed() {
        assertEquals("Server B", label(row = 1L, page = 2L))
    }

    @Test
    fun aDeletedSource_readsTheDeletedText() {
        assertEquals(DELETED, label(row = 9L, page = 2L))
    }

    @Test
    fun aLegacyRow_hasNoLabel_evenOnAnotherServersPage() {
        assertNull(label(row = 0L, page = 1L, active = 2L))
    }

    @Test
    fun aPageWithoutSource_isTheActiveProfiles() {
        assertNull(label(row = 2L, page = 0L, active = 2L))
        assertEquals("Server B", label(row = 1L, page = 0L, active = 2L))
    }

    private companion object {
        const val DELETED = "Source deleted"
    }
}
