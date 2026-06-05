package com.lanraragi.reader.client.api.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for LRRArchive → Archive / ArchiveDetail domain model conversion.
 *
 * Note: These tests create LRRArchive instances directly and call toArchive()/toArchiveDetail().
 * LRRAuthManager static calls return defaults (null serverUrl, 0 profileId) in test context,
 * which is acceptable — we verify the mapping logic, not the auth state.
 */
class LRRArchiveMapperTest {

    private fun createLRRArchive(
        arcid: String = "abc123def456",
        title: String = "Test Archive",
        tags: String = "artist:test_artist, parody:test_series, misc_tag",
        isnew: String = "true",
        extension: String = "zip",
        filename: String = "test.zip",
        pagecount: Int = 42,
        progress: Int = 10,
        lastreadtime: Long = 1700000000L,
        summary: String? = "A test summary",
    ): LRRArchive {
        val archive = LRRArchive()
        archive.arcid = arcid
        archive.title = title
        archive.tags = tags
        archive.isnew = isnew
        archive.extension = extension
        archive.filename = filename
        archive.pagecount = pagecount
        archive.progress = progress
        archive.lastreadtime = lastreadtime
        archive.summary = summary
        return archive
    }

    @Test
    fun `toArchive preserves arcid as String`() {
        val lrr = createLRRArchive(arcid = "a1b2c3d4e5f6")
        val archive = lrr.toArchive()
        assertEquals("a1b2c3d4e5f6", archive.arcid)
    }

    @Test
    fun `toArchive maps title`() {
        val archive = createLRRArchive(title = "My Title").toArchive()
        assertEquals("My Title", archive.title)
    }

    @Test
    fun `toArchive parses tags into namespace map`() {
        val archive = createLRRArchive(tags = "artist:author_a, parody:series_x, misc_tag").toArchive()
        assertEquals(3, archive.tags.size)
        assertEquals(listOf("author_a"), archive.tags["artist"])
        assertEquals(listOf("series_x"), archive.tags["parody"])
        assertEquals(listOf("misc_tag"), archive.tags["misc"])
    }

    @Test
    fun `toArchive handles empty tags`() {
        val archive = createLRRArchive(tags = "").toArchive()
        assertTrue(archive.tags.isEmpty())
    }

    @Test
    fun `toArchive maps pagecount and progress`() {
        val archive = createLRRArchive(pagecount = 100, progress = 50).toArchive()
        assertEquals(100, archive.pagecount)
        assertEquals(50, archive.progress)
    }

    @Test
    fun `toArchive maps extension and filename`() {
        val archive = createLRRArchive(extension = "cbz", filename = "test.cbz").toArchive()
        assertEquals("cbz", archive.extension)
        assertEquals("test.cbz", archive.filename)
    }

    @Test
    fun `toArchive extracts rating from tags`() {
        val archive = createLRRArchive(tags = "artist:someone, rating:⭐⭐⭐⭐").toArchive()
        assertEquals(4.0f, archive.rating)
    }

    @Test
    fun `toArchive returns negative rating when no rating tag`() {
        val archive = createLRRArchive(tags = "artist:someone").toArchive()
        assertEquals(-1.0f, archive.rating)
    }

    @Test
    fun `toArchive maps isnew`() {
        val archiveNew = createLRRArchive(isnew = "true").toArchive()
        assertTrue(archiveNew.isnew)

        val archiveOld = createLRRArchive(isnew = "false").toArchive()
        assertFalse(archiveOld.isnew)
    }

    @Test
    fun `toArchive maps lastreadtime and summary`() {
        val archive = createLRRArchive(lastreadtime = 12345L, summary = "desc").toArchive()
        assertEquals(12345L, archive.lastreadtime)
        assertEquals("desc", archive.summary)
    }

    @Test
    fun `toArchive handles null summary`() {
        val archive = createLRRArchive(summary = null).toArchive()
        assertNull(archive.summary)
    }

    @Test
    fun `toArchive thumbnailUrl is empty when no server URL`() {
        // LRRAuthManager.getServerUrl() returns null in test context
        val archive = createLRRArchive().toArchive()
        assertEquals("", archive.thumbnailUrl)
    }

    @Test
    fun `toArchiveDetail creates tagGroups from parsed tags`() {
        val detail = createLRRArchive(tags = "artist:author_a, parody:series_x").toArchiveDetail()
        assertEquals(2, detail.tagGroups.size)
        assertEquals("artist", detail.tagGroups[0].namespace)
        assertEquals(listOf("author_a"), detail.tagGroups[0].tags)
        assertEquals("parody", detail.tagGroups[1].namespace)
        assertEquals(listOf("series_x"), detail.tagGroups[1].tags)
    }

    @Test
    fun `toArchiveDetail sets language to N-A`() {
        val detail = createLRRArchive().toArchiveDetail()
        assertEquals("N/A", detail.language)
    }

    @Test
    fun `toArchiveDetail sets size from extension uppercase`() {
        val detail = createLRRArchive(extension = "cbz").toArchiveDetail()
        assertEquals("CBZ", detail.size)
    }

    @Test
    fun `toArchiveDetail sets size to N-A for empty extension`() {
        val detail = createLRRArchive(extension = "").toArchiveDetail()
        assertEquals("N/A", detail.size)
    }

    @Test
    fun `toArchiveDetail archive field is consistent with toArchive`() {
        val lrr = createLRRArchive()
        val archive = lrr.toArchive()
        val detail = lrr.toArchiveDetail()
        assertEquals(archive.arcid, detail.archive.arcid)
        assertEquals(archive.title, detail.archive.title)
        assertEquals(archive.pagecount, detail.archive.pagecount)
    }

    @Test
    fun `toArchiveList converts all results`() {
        val result = LRRSearchResult()
        result.data = listOf(
            createLRRArchive(arcid = "id1", title = "First"),
            createLRRArchive(arcid = "id2", title = "Second"),
        )
        result.recordsFiltered = 2
        result.recordsTotal = 10

        val archives = result.toArchiveList()
        assertEquals(2, archives.size)
        assertEquals("id1", archives[0].arcid)
        assertEquals("id2", archives[1].arcid)
    }

    @Test
    fun `toArchiveList returns empty for empty results`() {
        val result = LRRSearchResult()
        result.data = emptyList()
        assertTrue(result.toArchiveList().isEmpty())
    }

    @Test
    fun `toArchiveList drops tankoubon entries`() {
        // groupby_tanks returns 15-char TANK_ ids the archive pipeline can't
        // render; the list mapper must drop them rather than throw.
        val result = LRRSearchResult()
        result.data = listOf(
            createLRRArchive(arcid = "0".repeat(40), title = "Real archive"),
            createLRRArchive(arcid = "TANK_1688616437", title = "A tankoubon"),
        )

        val archives = result.toArchiveList()
        assertEquals(1, archives.size)
        assertEquals("0".repeat(40), archives[0].arcid)
    }

    @Test
    fun `toArchive accepts explicit sourceProfileId for cross-server fetches`() {
        // Regression: when the gallery-detail VM fetches metadata against
        // a non-active profile (i.e. archive originally downloaded from
        // server B while user is connected to server A), toArchive() must
        // tag the resulting Archive with the *source* profile id, NOT
        // the active one. Without the explicit parameter the in-memory
        // _archiveDetail and the LRU detail cache would be corrupted to
        // point at the active profile, flipping the source-server badge
        // and re-routing subsequent refreshes to the wrong server.
        // Use a 40-char SHA-1 arcid because getThumbnailUrl() validates.
        val archive = createLRRArchive(arcid = "0".repeat(40)).toArchive(
            sourceProfileId = 42L,
            sourceBaseUrl = "http://server-b.local:3000",
        )
        assertEquals(42L, archive.serverProfileId)
        // Thumbnail URL must be built against the source server, not the
        // active one — otherwise a cache miss falls through to a 404 on
        // the active server (which doesn't have this arcid).
        assertTrue(
            "thumbnailUrl built for source server, was: ${archive.thumbnailUrl}",
            archive.thumbnailUrl.startsWith("http://server-b.local:3000/"),
        )
    }

    @Test
    fun `toArchiveDetail threads source context into nested archive`() {
        val ad = createLRRArchive(arcid = "0".repeat(40)).toArchiveDetail(
            sourceProfileId = 7L,
            sourceBaseUrl = "http://server-c.local:3000",
        )
        assertEquals(7L, ad.archive.serverProfileId)
        assertTrue(ad.archive.thumbnailUrl.startsWith("http://server-c.local:3000/"))
    }
}
