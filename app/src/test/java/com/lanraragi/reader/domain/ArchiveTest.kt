package com.lanraragi.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveTest {

    private fun createArchive(
        arcid: String = "abc123def456",
        title: String = "Test Archive",
        tags: Map<String, List<String>> = mapOf("artist" to listOf("test_artist")),
        pagecount: Int = 42,
        progress: Int = 10,
        extension: String = "zip",
        filename: String = "test.zip",
        thumbnailUrl: String = "http://localhost/api/archives/abc123def456/thumbnail",
        rating: Float = 4.0f,
        isnew: Boolean = false,
        lastreadtime: Long = 1700000000L,
        summary: String? = "A test archive",
        serverProfileId: Long = 1L,
    ) = Archive(
        arcid = arcid,
        title = title,
        tags = tags,
        pagecount = pagecount,
        progress = progress,
        extension = extension,
        filename = filename,
        thumbnailUrl = thumbnailUrl,
        rating = rating,
        isnew = isnew,
        lastreadtime = lastreadtime,
        summary = summary,
        serverProfileId = serverProfileId,
    )

    @Test
    fun `data class equality - same fields are equal`() {
        val a = createArchive()
        val b = createArchive()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class equality - different fields are not equal`() {
        val a = createArchive(arcid = "aaa")
        val b = createArchive(arcid = "bbb")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy produces correct result`() {
        val original = createArchive(title = "Original")
        val copied = original.copy(title = "Changed")
        assertEquals("Changed", copied.title)
        assertEquals(original.arcid, copied.arcid)
        assertEquals(original.pagecount, copied.pagecount)
    }

    @Test
    fun `empty tags map works`() {
        val archive = createArchive(tags = emptyMap())
        assertEquals(emptyMap<String, List<String>>(), archive.tags)
    }

    @Test
    fun `null summary works`() {
        val archive = createArchive(summary = null)
        assertNull(archive.summary)
    }

    @Test
    fun `multi-namespace tags preserved`() {
        val tags = mapOf(
            "artist" to listOf("artist_a", "artist_b"),
            "parody" to listOf("series_x"),
            "misc" to listOf("tag1", "tag2", "tag3"),
        )
        val archive = createArchive(tags = tags)
        assertEquals(3, archive.tags.size)
        assertEquals(listOf("artist_a", "artist_b"), archive.tags["artist"])
        assertEquals(listOf("series_x"), archive.tags["parody"])
        assertEquals(3, archive.tags["misc"]?.size)
    }

    @Test
    fun `arcid is String not Long`() {
        val archive = createArchive(arcid = "a1b2c3d4e5f6a7b8c9d0")
        assertEquals("a1b2c3d4e5f6a7b8c9d0", archive.arcid)
    }
}

class ArchiveDetailTest {

    @Test
    fun `composition - archive field accessible`() {
        val archive = Archive(
            arcid = "test123",
            title = "Detail Test",
            tags = emptyMap(),
            pagecount = 10,
            progress = 0,
            extension = "zip",
            filename = "test.zip",
            thumbnailUrl = "",
            rating = -1f,
            isnew = true,
            lastreadtime = 0L,
            summary = null,
            serverProfileId = 1L,
        )
        val detail = ArchiveDetail(
            archive = archive,
            tagGroups = listOf(
                TagGroup("artist", listOf("someone")),
                TagGroup("misc", listOf("tag1", "tag2")),
            ),
            language = "N/A",
            size = "ZIP",
        )
        assertEquals("test123", detail.archive.arcid)
        assertEquals("Detail Test", detail.archive.title)
        assertEquals(2, detail.tagGroups.size)
        assertEquals("artist", detail.tagGroups[0].namespace)
        assertEquals(listOf("someone"), detail.tagGroups[0].tags)
        assertEquals("N/A", detail.language)
        assertEquals("ZIP", detail.size)
    }

    @Test
    fun `equality`() {
        val archive = Archive("id", "t", emptyMap(), 1, 0, "", "", "", 0f, false, 0, null, 0)
        val a = ArchiveDetail(archive, emptyList(), null, null)
        val b = ArchiveDetail(archive, emptyList(), null, null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}

class TagGroupTest {

    @Test
    fun `equality`() {
        val a = TagGroup("artist", listOf("a", "b"))
        val b = TagGroup("artist", listOf("a", "b"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different namespace not equal`() {
        val a = TagGroup("artist", listOf("a"))
        val b = TagGroup("parody", listOf("a"))
        assertNotEquals(a, b)
    }

    @Test
    fun `copy works`() {
        val original = TagGroup("misc", listOf("x"))
        val copied = original.copy(tags = listOf("x", "y"))
        assertEquals(2, copied.tags.size)
        assertEquals("misc", copied.namespace)
    }
}
