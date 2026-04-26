package com.hippo.ehviewer.mapper

import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryTagGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [GalleryInfoMapper] — the mapper extensions that bridge
 * Room Entities to the [Archive] / [ArchiveDetail] domain models.
 *
 * Pre-W36-11 this file also covered `GalleryInfo.toArchive()`. That
 * mapper was removed when GalleryDetail stopped extending
 * GalleryInfoEntity (no remaining caller); only the
 * GalleryDetail.toArchiveDetail path stayed live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GalleryInfoMapperTest {

    @Test
    fun `toArchiveDetail converts tag groups`() {
        val gd = GalleryDetail()
        gd.arcid = "detail1"
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
        gd.arcid = "detail2"
        gd.tags = null
        gd.serverProfileId = 1L

        val detail = gd.toArchiveDetail()
        assertTrue(detail.tagGroups.isEmpty())
        assertTrue(detail.archive.tags.isEmpty())
    }

    @Test
    fun `toArchiveDetail maps basic archive fields`() {
        val gd = GalleryDetail()
        gd.arcid = "arcid99"
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
        gd.arcid = "x"
        gd.serverProfileId = 1L

        val group = GalleryTagGroup()
        group.groupName = null
        group.addTag("some_tag")
        gd.tags = arrayOf(group)

        val detail = gd.toArchiveDetail()
        assertEquals("misc", detail.tagGroups[0].namespace)
    }

    @Test
    fun `toArchive on GalleryDetail maps fields and tags`() {
        val gd = GalleryDetail()
        gd.arcid = "ga1"
        gd.title = "Direct Archive"
        gd.thumb = "https://example.com/g.jpg"
        gd.pages = 33
        gd.progress = 7
        gd.rating = 3.0f
        gd.serverProfileId = 9L

        val group = GalleryTagGroup()
        group.groupName = "artist"
        group.addTag("alice")
        gd.tags = arrayOf(group)

        val archive = gd.toArchive()
        assertEquals("ga1", archive.arcid)
        assertEquals("Direct Archive", archive.title)
        assertEquals("https://example.com/g.jpg", archive.thumbnailUrl)
        assertEquals(33, archive.pagecount)
        assertEquals(7, archive.progress)
        assertEquals(3.0f, archive.rating)
        assertEquals(9L, archive.serverProfileId)
        assertEquals(listOf("alice"), archive.tags["artist"])
    }
}
