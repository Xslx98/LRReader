package com.hippo.ehviewer.mapper

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
    fun `archive to download info preserves display fields`() {
        val a = archive()
        val di = ArchiveMappers.archiveToDownloadInfo.map(a)
        assertEquals("abc", di.arcid)
        assertEquals("T", di.title)
        assertEquals(3.5f, di.rating)
    }

    @Test
    fun `download info to archive preserves arcid title rating profile`() {
        // The DownloadInfo.@Ignore simpleTags is a flat array, so round-
        // tripping through DownloadInfo loses tag namespaces (everything
        // collapses into "misc"). This is a known legacy-entity loss the
        // L1 audit item is set up to remove; lock the current behavior so
        // the lossless replacement gets caught when it lands.
        val a = archive()
        val di = ArchiveMappers.archiveToDownloadInfo.map(a)
        val back = ArchiveMappers.downloadInfoToArchive.map(di)
        assertEquals("abc", back.arcid)
        assertEquals("T", back.title)
        assertEquals(3.5f, back.rating)
        assertEquals(9L, back.serverProfileId)
        assertEquals(listOf("alice"), back.tags["misc"])
    }

    @Test
    fun `bimapper exposes both directions`() {
        val a = archive()
        val di = ArchiveMappers.downloadInfoBimapper.map(a)
        val back = ArchiveMappers.downloadInfoBimapper.reverse(di)
        assertEquals("abc", back.arcid)
    }

    @Test
    fun `history bimapper preserves rating`() {
        // Same legacy tag-namespace loss as the download bimapper above.
        val a = archive()
        val hi = ArchiveMappers.historyInfoBimapper.map(a)
        val back = ArchiveMappers.historyInfoBimapper.reverse(hi)
        assertEquals(3.5f, back.rating)
        assertEquals(listOf("alice"), back.tags["misc"])
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
}
