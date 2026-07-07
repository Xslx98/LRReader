package com.hippo.ehviewer.mapper

import com.lanraragi.reader.domain.Archive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the entity ↔ Archive bridge extensions in [EntityMapper].
 *
 * The GalleryDetail extensions were removed in M1b-5 along with the
 * GalleryDetail class itself — those scenarios are now covered by
 * [com.lanraragi.reader.client.api.data.LRRArchiveTest] (LRRArchive →
 * Archive / ArchiveDetail) and the round-trip suite in
 * [ArchiveMappersTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EntityMapperTest {

    private fun archive(): Archive = Archive(
        arcid = "ga1",
        title = "Direct Archive",
        tags = mapOf("artist" to listOf("alice", "bob"), "language" to listOf("english")),
        pagecount = 33,
        progress = 7,
        extension = "zip",
        filename = "ga1.zip",
        thumbnailUrl = "https://example.com/g.jpg",
        rating = 3.0f,
        isnew = false,
        lastreadtime = 1700_000L,
        summary = null,
        serverProfileId = 9L,
    )

    @Test
    fun `Archive toDownloadInfoView populates display fields`() {
        val di = archive().toDownloadInfoView()

        assertEquals("ga1", di.arcid)
        assertEquals("Direct Archive", di.title)
        assertEquals("https://example.com/g.jpg", di.thumb)
        assertEquals(3.0f, di.rating)
        assertEquals(9L, di.serverProfileId)
        // simpleTags is the flattened "namespace:value" array consumed by
        // the search index; verify it round-trips through the Archive
        // tag map.
        val flat = di.simpleTags?.toList()
        assertEquals(listOf("alice", "bob", "english"), flat)
    }

    @Test
    fun `Archive toHistoryInfoView populates display fields`() {
        val hi = archive().toHistoryInfoView()

        assertEquals("ga1", hi.arcid)
        assertEquals("Direct Archive", hi.title)
        assertEquals("https://example.com/g.jpg", hi.thumb)
        assertEquals(3.0f, hi.rating)
        assertEquals(9L, hi.serverProfileId)
    }

    @Test
    fun `Archive toDegradedArchiveDetail seeds tagGroups for cache-first render`() {
        // The detail page falls back to this mapper when the live LRR
        // metadata fetch hasn't returned (cross-server source offline,
        // orphan profile, server-side delete) but the navigation arg
        // already carries a usable Archive snapshot. The tag groups
        // need to ride through unchanged so the binder can paint the
        // tag rows; language/size are server-only and collapse to null.
        val ad = archive().toDegradedArchiveDetail()

        assertEquals("ga1", ad.archive.arcid)
        assertEquals("Direct Archive", ad.archive.title)
        // tagGroups preserves the source map's namespaces with their tags.
        val byNs = ad.tagGroups.associate { it.namespace to it.tags }
        assertEquals(listOf("alice", "bob"), byNs["artist"])
        assertEquals(listOf("english"), byNs["language"])
        // language / size are derived from server-only LRRArchive fields
        // we don't have on the navigation Archive — null is the contract.
        assertEquals(null, ad.language)
        assertEquals(null, ad.size)
    }

    @Test
    fun `HistoryInfo toArchive converts millisecond view time to epoch-second lastreadtime`() {
        // HISTORY_TIME column semantics are device milliseconds; the Archive
        // field (and thus any persisted archive_json built from this mapper,
        // e.g. HistoryRepository.putHistoryInfoList) is epoch SECONDS —
        // LANraragi `lastreadtime` semantics.
        val hi = archive().toHistoryInfoView()
        hi.time = 1_700_000_001_234L

        assertEquals(1_700_000_001L, hi.toArchive().lastreadtime)
    }

    @Test
    fun `DownloadInfo toArchive groups simple tags by namespace`() {
        val di = archive().toDownloadInfoView()
        // Mimic a tag-namespaced flat array as written by the legacy
        // import path; the forward mapper writes namespace-less values,
        // but the inverse parser must still cope.
        di.simpleTags = arrayOf("artist:alice", "language:english", "raw")
        val back = di.toArchive()

        assertEquals(listOf("alice"), back.tags["artist"])
        assertEquals(listOf("english"), back.tags["language"])
        assertEquals(listOf("raw"), back.tags["misc"])
    }
}
