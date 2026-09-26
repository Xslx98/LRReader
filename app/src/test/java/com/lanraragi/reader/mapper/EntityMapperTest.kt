package com.lanraragi.reader.mapper

import com.lanraragi.reader.dao.ArchiveLocalState
import com.lanraragi.reader.dao.DownloadObservedRow
import com.lanraragi.reader.dao.HistoryInfo
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.download.DownloadState
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
        // simpleTags is the flattened "namespace:value" form the views carry
        // (and the CSV export writes); groupFlatTags reads it back.
        val flat = di.simpleTags?.toList()
        assertEquals(listOf("artist:alice", "artist:bob", "language:english"), flat)
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
        // field (and thus any archive_json built from this mapper) is epoch
        // SECONDS — LANraragi `lastreadtime` semantics.
        val hi = HistoryInfo().apply {
            arcid = "ga1"
            time = 1_700_000_001_234L
        }

        assertEquals(1_700_000_001L, hi.toArchive().lastreadtime)
    }

    // Downloading an archive persisted its tags through the flat view
    // (startDownload -> history write). The views used to flatten to bare
    // values, so every tag came back under misc: the stats page then counted
    // artists and languages as plain tags. Each view producer must round-trip.
    private val namespacedTags = mapOf(
        "artist" to listOf("alice", "re:zero"),
        "language" to listOf("english"),
        "misc" to listOf("raw", "re:zero"),
    )

    private fun storedRow() = ArchiveLocalState(
        arcid = "ga1",
        serverProfileId = 9L,
        archiveJson = archive().copy(tags = namespacedTags).toArchiveJson(),
        downloadState = DownloadState.FINISH,
        historyTime = 1_700_000_001_234L,
    )

    @Test
    fun `DownloadInfo view round-trips tag namespaces`() {
        val original = archive().copy(tags = namespacedTags)

        assertEquals(namespacedTags, original.toDownloadInfoView().toArchive().tags)
    }

    @Test
    fun `HistoryInfo view built from a stored row round-trips tag namespaces`() {
        assertEquals(namespacedTags, storedRow().toHistoryInfoView().toArchive().tags)
    }

    @Test
    fun `DownloadInfo views built from stored rows round-trip tag namespaces`() {
        val row = storedRow()
        val observed = DownloadObservedRow(
            arcid = row.arcid, serverProfileId = row.serverProfileId, archiveJson = row.archiveJson,
            downloadState = DownloadState.FINISH, downloadLegacy = 0, downloadTime = 1L,
            downloadLabel = null, downloadArchiveUri = null, downloadRootUri = null, downloadTankId = null,
        )

        assertEquals(namespacedTags, row.toDownloadInfoView().toArchive().tags)
        assertEquals(namespacedTags, observed.toDownloadInfoView().toArchive().tags)
    }

    @Test
    fun `pagecount rides the DownloadInfo view both ways`() {
        val di = archive().toDownloadInfoView()
        assertEquals(33, di.pagecount)
        assertEquals(33, di.toArchive().pagecount)
    }
}
