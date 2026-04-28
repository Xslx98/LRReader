package com.hippo.ehviewer.mapper

import com.hippo.ehviewer.dao.ArchiveLocalState
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.Mapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ArchiveMappersTest {

    private fun archive(arcid: String = "abc"): Archive = Archive(
        arcid = arcid,
        title = "T",
        tags = mapOf("artist" to listOf("alice")),
        pagecount = 12,
        progress = 3,
        extension = "zip",
        filename = "t.zip",
        thumbnailUrl = "https://example.test/t",
        rating = 3.5f,
        isnew = false,
        lastreadtime = 100L,
        summary = "s",
        serverProfileId = 9L,
    )

    @Test
    fun `archiveLocalStateToArchive decodes the archive_json payload`() {
        val source = archive("rt-1")
        val row = ArchiveLocalState(
            arcid = "rt-1",
            serverProfileId = 9L,
            archiveJson = source.toArchiveJson(),
            historyTime = 1700_000L,
            historyMode = 0,
        )
        val decoded = ArchiveMappers.archiveLocalStateToArchive.map(row)
        assertEquals("rt-1", decoded.arcid)
        assertEquals("T", decoded.title)
        assertEquals(3.5f, decoded.rating)
        assertEquals(9L, decoded.serverProfileId)
        assertEquals(listOf("alice"), decoded.tags["artist"])
    }

    @Test
    fun `mapper interface accepts ad-hoc lambda implementations for tests`() {
        val fake: Mapper<Archive, String> = Mapper { it.arcid + ":" + it.title }
        assertEquals("abc:T", fake.map(archive()))
    }

    @Test
    fun `lrrArchiveToArchive registry entry is wired`() {
        // Smoke check that the mapper instance exists and is callable.
        // The actual conversion is exercised by LRRArchiveTest; here we
        // just want to be sure the registry constant is non-null.
        assertNotNull(ArchiveMappers.lrrArchiveToArchive)
    }

    @Test
    fun `lrrArchiveToArchiveDetail registry entry is wired`() {
        assertNotNull(ArchiveMappers.lrrArchiveToArchiveDetail)
    }
}
