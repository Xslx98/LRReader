package com.hippo.ehviewer.mapper

import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.GalleryTagGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for GalleryInfo → Archive and GalleryDetail → ArchiveDetail mapper
 * extension functions in [GalleryInfoMapper].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GalleryInfoMapperTest {

    // -------------------------------------------------------------------------
    // GalleryInfo.toArchive()
    // -------------------------------------------------------------------------

    @Test
    fun `toArchive defaults lastreadtime to 0`() {
        val gi = GalleryInfo()
        gi.token = "abc123"
        gi.title = "Test"
        val archive = gi.toArchive()
        assertEquals(0L, archive.lastreadtime)
    }

    @Test
    fun `toArchive preserves explicit lastreadtime`() {
        val gi = GalleryInfo()
        gi.token = "abc123"
        gi.title = "Test"
        val archive = gi.toArchive(lastreadtime = 1700000000L)
        assertEquals(1700000000L, archive.lastreadtime)
    }

    @Test
    fun `toArchive maps basic fields`() {
        val gi = GalleryInfo()
        gi.token = "id42"
        gi.title = "My Archive"
        gi.thumb = "https://example.com/thumb.jpg"
        gi.rating = 3.5f
        gi.pages = 100
        gi.progress = 42
        gi.serverProfileId = 7L

        val archive = gi.toArchive()
        assertEquals("id42", archive.arcid)
        assertEquals("My Archive", archive.title)
        assertEquals("https://example.com/thumb.jpg", archive.thumbnailUrl)
        assertEquals(3.5f, archive.rating)
        assertEquals(100, archive.pagecount)
        assertEquals(42, archive.progress)
        assertEquals(7L, archive.serverProfileId)
    }

    @Test
    fun `toArchive parses simpleTags into namespace map`() {
        val gi = GalleryInfo()
        gi.token = "x"
        gi.simpleTags = arrayOf("artist:someone", "parody:series", "misc_tag")

        val archive = gi.toArchive()
        assertEquals(listOf("someone"), archive.tags["artist"])
        assertEquals(listOf("series"), archive.tags["parody"])
        assertEquals(listOf("misc_tag"), archive.tags["misc"])
    }

    @Test
    fun `toArchive handles null simpleTags`() {
        val gi = GalleryInfo()
        gi.token = "x"
        gi.simpleTags = null

        val archive = gi.toArchive()
        assertTrue(archive.tags.isEmpty())
    }

    // -------------------------------------------------------------------------
    // GalleryDetail.toArchiveDetail()
    // -------------------------------------------------------------------------

    @Test
    fun `toArchiveDetail converts tag groups`() {
        val gd = GalleryDetail()
        gd.token = "detail1"
        gd.title = "Detail Test"
        gd.language = "Japanese"
        gd.size = "150 MB"
        gd.pages = 50
        gd.rating = 4.0f
        gd.serverProfileId = 1L

        val group1 = GalleryTagGroup()
        group1.groupName = "artist"
        group1.addTag("author_a")
        group1.addTag("author_b")

        val group2 = GalleryTagGroup()
        group2.groupName = "parody"
        group2.addTag("series_x")

        gd.tags = arrayOf(group1, group2)

        val detail = gd.toArchiveDetail()
        assertEquals(2, detail.tagGroups.size)
        assertEquals("artist", detail.tagGroups[0].namespace)
        assertEquals(listOf("author_a", "author_b"), detail.tagGroups[0].tags)
        assertEquals("parody", detail.tagGroups[1].namespace)
        assertEquals(listOf("series_x"), detail.tagGroups[1].tags)
        assertEquals("Japanese", detail.language)
        assertEquals("150 MB", detail.size)
    }

    @Test
    fun `toArchiveDetail handles null tags`() {
        val gd = GalleryDetail()
        gd.token = "detail2"
        gd.tags = null
        gd.serverProfileId = 1L

        val detail = gd.toArchiveDetail()
        assertTrue(detail.tagGroups.isEmpty())
        assertTrue(detail.archive.tags.isEmpty())
    }

    @Test
    fun `toArchiveDetail maps basic archive fields`() {
        val gd = GalleryDetail()
        gd.token = "arcid99"
        gd.title = "Title"
        gd.thumb = "https://example.com/t.jpg"
        gd.pages = 20
        gd.progress = 5
        gd.rating = 2.5f
        gd.serverProfileId = 3L
        gd.tags = emptyArray()

        val detail = gd.toArchiveDetail()
        assertEquals("arcid99", detail.archive.arcid)
        assertEquals("Title", detail.archive.title)
        assertEquals("https://example.com/t.jpg", detail.archive.thumbnailUrl)
        assertEquals(20, detail.archive.pagecount)
        assertEquals(5, detail.archive.progress)
        assertEquals(2.5f, detail.archive.rating)
        assertEquals(3L, detail.archive.serverProfileId)
    }

    @Test
    fun `toArchiveDetail uses misc for null groupName`() {
        val gd = GalleryDetail()
        gd.token = "x"
        gd.serverProfileId = 1L

        val group = GalleryTagGroup()
        group.groupName = null
        group.addTag("some_tag")
        gd.tags = arrayOf(group)

        val detail = gd.toArchiveDetail()
        assertEquals("misc", detail.tagGroups[0].namespace)
    }
}
