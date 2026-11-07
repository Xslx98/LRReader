package com.lanraragi.reader.contract

import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRSearchApi
import com.lanraragi.reader.client.api.archiveThumbnailUrl
import com.lanraragi.reader.client.api.resolvePageUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Archive endpoints: the DTO fields and the behaviours the client's readers rely on. */
class ArchiveContractTest {

    private val client get() = ContractServer.client
    private val url get() = ContractServer.baseUrl
    private val seed get() = ContractServer.seed

    @Test
    fun computedArchiveId_equalsServerArcid_pastTheHashCutOff() {
        assertTrue("fixture must exceed the 512 000 bytes the id covers", File(seed.big.file).length() > 512_000)
        seed.archives.values.forEach { archive ->
            val computed = File(archive.file).inputStream().use { LRRArchiveApi.computeArchiveId(it) }
            assertEquals(archive.title, archive.id, computed)
        }
    }

    @Test
    fun metadata_decodesTheFieldsTheClientReads() = contractTest {
        val archive = LRRArchiveApi.getArchiveMetadata(client, url, seed.alpha.id)

        assertEquals(seed.alpha.id, archive.arcid)
        assertEquals(seed.alpha.title, archive.title)
        assertEquals(seed.alpha.pages, archive.pagecount)
        assertEquals("zip", archive.extension)
        assertTrue(archive.filename, archive.filename.startsWith("contract-alpha"))
        assertTrue(archive.tags, "artist:contract alpha" in archive.tags)
        assertTrue("a fresh upload is flagged new, got ${archive.isnew}", archive.isNew())
        assertEquals("rating tag", 4f, archive.toArchive(1L, url).rating)
    }

    @Test
    fun fileList_pagesResolveToTheArchiveBytesInOrder() = contractTest {
        val pages = LRRArchiveApi.getFileList(client, url, seed.big.id)
        assertEquals(seed.big.pages, pages.size)

        ZipFile(seed.big.file).use { zip ->
            val entries = zip.entries().toList()
            listOf(0, 17, 39).forEach { index ->
                val expected = zip.getInputStream(entries[index]).use { it.readBytes() }
                val served = rawGet(resolvePageUrl(url, pages[index]))
                assertTrue("page $index", expected.contentEquals(served))
            }
        }
    }

    @Test
    fun pageThumbnail_clientPageIndexIsZeroBased() = contractTest {
        listOf(0, 1, 39).forEach { page ->
            val thumbnail = awaitValue {
                LRRArchiveApi.fetchPageThumbnail(client, url, seed.big.id, page)
                    as? LRRArchiveApi.PageThumbnailFetchResult.Ready
            }
            assertEquals("client page $page", page, nearestPage(thumbnail.bytes, seed.big.pageRgb))
        }
    }

    @Test
    fun pageThumbnail_notYetGenerated_answersPendingWithAJob() = contractTest {
        // Uploads only generate the cover and nothing else asks for page 20, so on the
        // fresh container each run uses, the first answer must be the queueing one.
        val first = LRRArchiveApi.fetchPageThumbnail(client, url, seed.big.id, 20)
        assertTrue("first answer: $first", first is LRRArchiveApi.PageThumbnailFetchResult.Pending)
        assertNotNull("202 must name its job", (first as LRRArchiveApi.PageThumbnailFetchResult.Pending).jobId)
        val ready = awaitValue {
            LRRArchiveApi.fetchPageThumbnail(client, url, seed.big.id, 20)
                as? LRRArchiveApi.PageThumbnailFetchResult.Ready
        }
        assertEquals(20, nearestPage(ready.bytes, seed.big.pageRgb))
    }

    @Test
    fun updateThumbnail_setsTheCoverFromAOneBasedPage() = contractTest {
        val coverUrl = archiveThumbnailUrl(url, seed.charlie.id)
        LRRArchiveApi.updateThumbnail(client, url, seed.charlie.id, page1 = 2)
        try {
            awaitTrue { nearestPage(rawGet(coverUrl), seed.charlie.pageRgb) == 1 }
        } finally {
            LRRArchiveApi.updateThumbnail(client, url, seed.charlie.id, page1 = 1)
        }
    }

    @Test
    fun unknownArcid_is400WithTheServerMessage() = contractTest {
        val unknown = "0".repeat(40)
        val failure = assertHttpFailure(400) { LRRArchiveApi.getArchiveMetadata(client, url, unknown) }
        assertTrue("serverError: ${failure.serverError}", !failure.serverError.isNullOrBlank())
        assertFalse(LRRArchiveApi.archiveExists(client, url, unknown))
    }

    @Test
    fun updateMetadata_writesThroughQueryParameters() = contractTest {
        val id = seed.charlie.id
        val title = "Contract 契約 & co"
        val tags = seed.charlie.tags + ",contractedit"
        LRRArchiveApi.updateMetadata(client, url, id, title = title, tags = tags, summary = "contract summary")
        try {
            val updated = LRRArchiveApi.getArchiveMetadata(client, url, id)
            assertEquals(title, updated.title)
            assertEquals("contract summary", updated.summary)
            assertTrue(updated.tags, "contractedit" in updated.tags)

            val found = LRRSearchApi.searchArchives(
                client, url, "contractedit", null, 0, null, null, newonly = false,
            )
            assertEquals(listOf(id), found.data.map { it.arcid })
        } finally {
            LRRArchiveApi.updateMetadata(client, url, id, title = seed.charlie.title, tags = seed.charlie.tags)
        }
    }

    @Test
    fun updateArchiveMetadata_writesTheRatingTag() = contractTest {
        val id = seed.bravo.id
        LRRArchiveApi.updateArchiveMetadata(client, url, id, seed.bravo.tags + ",rating:2")
        try {
            val updated = LRRArchiveApi.getArchiveMetadata(client, url, id)
            assertEquals(2f, updated.toArchive(1L, url).rating)
        } finally {
            LRRArchiveApi.updateArchiveMetadata(client, url, id, seed.bravo.tags)
        }
    }

    @Test
    fun progress_roundTripsWithAnEpochSecondsReadTime() = contractTest {
        LRRArchiveApi.updateProgress(client, url, seed.bravo.id, 2)

        val archive = LRRArchiveApi.getArchiveMetadata(client, url, seed.bravo.id)
        assertEquals(2, archive.progress)
        assertTrue("lastreadtime ${archive.lastreadtime}", archive.lastreadtime in 1_600_000_000L..99_999_999_999L)
    }

    @Test
    fun upload_lifecycleAndItsErrorEnvelopes() = contractTest {
        val spare = File(seed.spare.file)
        val expectedId = spare.inputStream().use { LRRArchiveApi.computeArchiveId(it) }

        val mismatch = assertHttpFailure(417) {
            LRRArchiveApi.uploadArchive(client, url, spare, fileChecksum = "0".repeat(40))
        }
        assertTrue("serverError: ${mismatch.serverError}", !mismatch.serverError.isNullOrBlank())

        val id = LRRArchiveApi.uploadArchive(
            client, url, spare,
            title = seed.spare.title,
            fileChecksum = LRRArchiveApi.computeFileChecksum(spare),
        )
        var deleted = false
        try {
            assertEquals(expectedId, id)
            assertTrue(LRRArchiveApi.archiveExists(client, url, id))
            val uploaded = LRRArchiveApi.getArchiveMetadata(client, url, id)
            assertEquals(seed.spare.title, uploaded.title)
            assertTrue(uploaded.isNew())

            LRRArchiveApi.clearNewFlag(client, url, id)
            assertFalse(LRRArchiveApi.getArchiveMetadata(client, url, id).isNew())

            val duplicate = assertHttpFailure(409) { LRRArchiveApi.uploadArchive(client, url, spare) }
            assertTrue("serverError: ${duplicate.serverError}", !duplicate.serverError.isNullOrBlank())

            val deletedName = LRRArchiveApi.deleteArchive(client, url, id)
            deleted = true
            assertTrue(deletedName, "contract-spare" in deletedName)
            assertFalse(LRRArchiveApi.archiveExists(client, url, id))
        } finally {
            if (!deleted) LRRArchiveApi.deleteArchive(client, url, id)
        }
    }
}
